#!/usr/bin/env bash

# Seed the wrapper cache, then run the requested Gradle tasks once.

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

exec ./gradlew "$@"
