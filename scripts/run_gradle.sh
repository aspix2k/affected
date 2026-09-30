#!/usr/bin/env bash

# Seed the wrapper cache, then run the requested Gradle tasks.
# Retry only transient repository HTTP errors. A cache-redirector 5xx
# switches the next attempt to Maven Central first. A Central 429
# switches back to the JetBrains cache-redirector.
# When AFFECTED_GRADLE_HANG_DIR is set, a build that prints nothing for
# AFFECTED_GRADLE_IDLE_SECONDS gets a thread dump of every visible JVM in that
# directory, is terminated, and exits with status 124.

set -euo pipefail

if (( $# == 0 )); then
  echo "Usage: scripts/run_gradle.sh <gradle arguments>" >&2
  exit 2
fi

if [[ ! -f ./gradlew ]]; then
  echo "Gradle wrapper is missing." >&2
  exit 1
fi
chmod +x ./gradlew

if [[ "${AFFECTED_SKIP_GRADLE_FETCH:-}" != 1 ]]; then
  python3 "$(dirname "$0")/fetch_gradle.py"
fi

attempts=${AFFECTED_GRADLE_ATTEMPTS:-3}
sleep_seconds=${AFFECTED_GRADLE_RETRY_SLEEP:-2}
if [[ ! "$attempts" =~ ^[1-9][0-9]*$ ]]; then
  echo "AFFECTED_GRADLE_ATTEMPTS must be a positive integer." >&2
  exit 2
fi

hang_dir=${AFFECTED_GRADLE_HANG_DIR:-}
idle_seconds=${AFFECTED_GRADLE_IDLE_SECONDS:-900}
poll_seconds=${AFFECTED_GRADLE_IDLE_POLL:-30}
for setting in "$idle_seconds" "$poll_seconds"; do
  if [[ ! "$setting" =~ ^[1-9][0-9]*$ ]]; then
    echo "AFFECTED_GRADLE_IDLE_SECONDS and AFFECTED_GRADLE_IDLE_POLL must be positive integers." >&2
    exit 2
  fi
done

log=$(mktemp)
hang_marker=$(mktemp)
watchdog_pid=
trap 'rm -f -- "$log" "$hang_marker"' EXIT

descendants() {
  local child
  for child in $(pgrep -P "$1" || true); do
    echo "$child"
    descendants "$child"
  done
}

dump_jvms() {
  local jcmd_bin pid
  mkdir -p "$hang_dir"
  ps -eo pid,ppid,etime,args > "$hang_dir/processes.txt" 2>&1 || true
  jcmd_bin=$(command -v jcmd || true)
  if [[ -z "$jcmd_bin" && -x "${JAVA_HOME:-}/bin/jcmd" ]]; then
    jcmd_bin=$JAVA_HOME/bin/jcmd
  fi
  if [[ -z "$jcmd_bin" ]]; then
    echo "jcmd is unavailable; no thread dumps were captured." >&2
    return
  fi
  for pid in $("$jcmd_bin" -l | awk '$2 != "jdk.jcmd/sun.tools.jcmd.JCmd" && $2 != "sun.tools.jcmd.JCmd" {print $1}'); do
    "$jcmd_bin" "$pid" Thread.print -e > "$hang_dir/threads-$pid.txt" 2>&1 || true
  done
}

terminate_build() {
  local pids pid self
  self=$(sh -c 'echo $PPID')
  pids=$(descendants "$$" | grep -vx "$self" || true)
  for pid in $pids; do kill -TERM "$pid" 2>/dev/null || true; done
  sleep 5
  for pid in $pids; do kill -KILL "$pid" 2>/dev/null || true; done
}

watch_for_hang() {
  local size last_size=-1 quiet_since=$SECONDS
  while sleep "$poll_seconds"; do
    size=$(wc -c < "$log")
    if (( size != last_size )); then
      last_size=$size
      quiet_since=$SECONDS
    elif (( SECONDS - quiet_since >= idle_seconds )); then
      echo "Gradle printed nothing for ${idle_seconds}s; capturing thread dumps in $hang_dir." >&2
      echo hung > "$hang_marker"
      dump_jvms
      terminate_build
      return
    fi
  done
}

stop_watchdog() {
  if [[ -n "$watchdog_pid" ]]; then
    if [[ ! -s "$hang_marker" ]]; then
      kill "$watchdog_pid" 2>/dev/null || true
    fi
    wait "$watchdog_pid" 2>/dev/null || true
    watchdog_pid=
  fi
}

is_transient_repository_error() {
  grep -Eq 'Received status code (403|429|502|503|504) from server' "$log"
}

should_prefer_maven_central() {
  grep -Fq 'cache-redirector.jetbrains.com' "$log" && \
    grep -Eq 'Received status code (502|503|504) from server' "$log"
}

should_prefer_cache_redirector() {
  grep -Fq 'repo.maven.apache.org' "$log" && \
    grep -Eq 'Received status code 429 from server' "$log"
}

attempt=1
while :; do
  : > "$hang_marker"
  if [[ -n "$hang_dir" ]]; then
    watch_for_hang &
    watchdog_pid=$!
  fi
  set +e
  ./gradlew "$@" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  set -e
  stop_watchdog
  if [[ -s "$hang_marker" ]]; then
    echo "Gradle was terminated after ${idle_seconds}s without output." >&2
    exit 124
  fi
  if (( status == 0 )); then
    exit 0
  fi
  if (( attempt >= attempts )) || ! is_transient_repository_error; then
    exit "$status"
  fi
  if should_prefer_maven_central; then
    export AFFECTED_PREFER_MAVEN_CENTRAL=1
    echo "cache-redirector returned a server error; retrying with Maven Central first." >&2
  elif should_prefer_cache_redirector; then
    unset AFFECTED_PREFER_MAVEN_CENTRAL
    echo "Maven Central returned 429; retrying with cache-redirector first." >&2
  else
    echo "Retrying Gradle after a transient repository error ($attempt/$attempts)." >&2
  fi
  sleep "$sleep_seconds"
  attempt=$((attempt + 1))
done
