#!/usr/bin/env bash

# Fetch the Gradle distribution with retries, then run the requested tasks once.

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

is_transient() {
  grep -Eqi \
    'SocketException|Connection reset|Connection timed out|Unexpected end of file|UnknownHostException|No route to host|HTTP 5[0-9][0-9]|Gateway Time-out|Could not GET|Connection refused' \
    "$1"
}

tmp_root=${TMPDIR:-/tmp}
warmup_out=$(mktemp "${tmp_root%/}/affected-gradle-warmup.XXXXXX")
warmup_err=$(mktemp "${tmp_root%/}/affected-gradle-warmup.XXXXXX")
trap 'rm -f -- "$warmup_out" "$warmup_err"' EXIT

attempt=1
while (( attempt <= 5 )); do
  if ./gradlew --version >"$warmup_out" 2>"$warmup_err"; then
    break
  fi
  if ! is_transient "$warmup_err"; then
    cat "$warmup_out" "$warmup_err" >&2
    exit 1
  fi
  if (( attempt == 5 )); then
    cat "$warmup_out" "$warmup_err" >&2
    echo "Gradle distribution fetch failed after 5 attempts." >&2
    exit 1
  fi
  sleep $((2 ** (attempt - 1)))
  attempt=$((attempt + 1))
done

exec ./gradlew "$@"
