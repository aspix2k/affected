#!/usr/bin/env bash

# Seed the wrapper cache, then run the requested Gradle tasks.
# Retry only transient repository HTTP errors. A cache-redirector 5xx
# switches the next attempt to Maven Central first. A Central 429
# switches back to the JetBrains cache-redirector.

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

log=$(mktemp)
trap 'rm -f -- "$log"' EXIT

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
  set +e
  ./gradlew "$@" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  set -e
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
