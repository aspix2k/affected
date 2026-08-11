#!/usr/bin/env bash

set -euo pipefail

if ! command -v git >/dev/null 2>&1; then
  echo "git is required to enumerate tracked files." >&2
  exit 127
fi

mode=${1:-}
if (( $# != 1 )); then
  echo "Usage: scripts/quality.sh <shell|workflows>" >&2
  exit 2
fi

quality_tmp_root=${TMPDIR:-/tmp}

case "$mode" in
  shell)
    if ! command -v shellcheck >/dev/null 2>&1; then
      echo "shellcheck is required for shell analysis." >&2
      exit 127
    fi

    shell_files=$(mktemp "${quality_tmp_root%/}/affected-shell-files.XXXXXX")
    trap 'rm -f -- "$shell_files"' EXIT
    git ls-files -z -- '*.sh' gradlew > "$shell_files"

    scripts=()
    while IFS= read -r -d '' path; do
      scripts+=("$path")
    done < "$shell_files"
    if (( ${#scripts[@]} == 0 )); then
      echo "No tracked shell scripts found." >&2
      exit 1
    fi

    shellcheck -- "${scripts[@]}"
    ;;
  workflows)
    if ! command -v actionlint >/dev/null 2>&1; then
      echo "actionlint is required for workflow analysis." >&2
      exit 127
    fi

    workflow_files=$(mktemp "${quality_tmp_root%/}/affected-workflow-files.XXXXXX")
    trap 'rm -f -- "$workflow_files"' EXIT
    git ls-files -z -- '.github/workflows/*.yml' '.github/workflows/*.yaml' > "$workflow_files"

    release=.github/workflows/release.yml
    release_found=false
    workflows=()
    while IFS= read -r -d '' path; do
      if [[ "$path" == "$release" ]]; then
        release_found=true
      else
        workflows+=("$path")
      fi
    done < "$workflow_files"
    if [[ "$release_found" != true ]]; then
      echo "The tracked release workflow is missing." >&2
      exit 1
    fi

    if (( ${#workflows[@]} > 0 )); then
      actionlint "${workflows[@]}"
    fi
    if ! awk '
      /^[[:space:]]+queue:/ { total++ }
      /^concurrency:$/ { in_concurrency = 1; next }
      in_concurrency && /^[^[:space:]]/ { in_concurrency = 0 }
      in_concurrency && /^  queue: max$/ { valid++ }
      END { exit !(total == 1 && valid == 1) }
    ' "$release"; then
      echo "release.yml must contain exactly one top-level concurrency queue: max." >&2
      exit 1
    fi
    actionlint -ignore 'unexpected key "queue" for "concurrency" section' "$release"
    ;;
  *)
    echo "Usage: scripts/quality.sh <shell|workflows>" >&2
    exit 2
    ;;
esac
