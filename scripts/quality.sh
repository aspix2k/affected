#!/usr/bin/env bash

set -euo pipefail

if ! command -v git >/dev/null 2>&1; then
  echo "git is required to enumerate tracked files." >&2
  exit 127
fi

mode=${1:-}
if (( $# == 0 || $# > 2 )); then
  echo "Usage: scripts/quality.sh <analyzers|codeql|dependency-graph|shell|workflows> [input directory]" >&2
  exit 2
fi

quality_tmp_root=${TMPDIR:-/tmp}

case "$mode" in
  analyzers)
    if (( $# != 1 )); then
      echo "Usage: scripts/quality.sh analyzers" >&2
      exit 2
    fi

    analyzer_files=$(mktemp "${quality_tmp_root%/}/affected-analyzer-files.XXXXXX")
    analyzer_findings=$(mktemp "${quality_tmp_root%/}/affected-analyzer-findings.XXXXXX")
    trap 'rm -f -- "$analyzer_files" "$analyzer_findings"' EXIT
    git ls-files -z > "$analyzer_files"

    baseline_files=()
    shopt -s nocasematch
    while IFS= read -r -d '' path; do
      if [[ "$path" =~ (^|/)(detekt|spotbugs|dependency-analysis)[^/]*baseline ]] ||
        [[ "$path" =~ (^|/)baseline[^/]*(detekt|spotbugs|dependency-analysis) ]]; then
        baseline_files+=("$path")
      fi
    done < "$analyzer_files"
    shopt -u nocasematch
    if (( ${#baseline_files[@]} > 0 )); then
      printf 'Analyzer baseline files are forbidden:\n' >&2
      printf '  %s\n' "${baseline_files[@]}" >&2
      exit 1
    fi

    configurable='baseline(File)?|excludeFilter|includeFilter|onlyAnalyze|omitVisitors'
    forbidden_config="($configurable)[[:space:]]*(\\.set[[:space:]]*\\(|[:=])|ignoreFailures[[:space:]]*(=|\\.set[[:space:]]*\\()|severity\\([[:space:]]*\"ignore\""
    grep_status=0
    git grep -nE "$forbidden_config" -- '*.gradle' '*.gradle.kts' 'config/*.yml' 'config/*.yaml' \
      > "$analyzer_findings" || grep_status=$?
    if (( grep_status > 1 )); then
      echo "Unable to inspect analyzer configuration." >&2
      exit "$grep_status"
    fi
    if [[ -s "$analyzer_findings" ]]; then
      invalid_findings=()
      while IFS= read -r finding; do
        value=${finding#*:*:}
        value=${value#"${value%%[![:space:]]*}"}
        value=${value%"${value##*[![:space:]]}"}
        if [[ "$value" == "ignoreFailures = false" ]]; then continue; fi
        invalid_findings+=("$finding")
      done < "$analyzer_findings"
      if (( ${#invalid_findings[@]} > 0 )); then
        printf '%s\n' "${invalid_findings[@]}" >&2
        echo "Analyzer baselines and fail-open settings are forbidden." >&2
        exit 1
      fi
    fi
    ;;
  codeql)
    if (( $# != 2 )); then
      echo "Usage: scripts/quality.sh codeql <SARIF directory>" >&2
      exit 2
    fi
    if ! command -v jq >/dev/null 2>&1; then
      echo "jq is required to validate CodeQL SARIF." >&2
      exit 127
    fi

    sarif_directory=$2
    if [[ ! -d "$sarif_directory" || ! -r "$sarif_directory" ]]; then
      echo "CodeQL SARIF directory is missing or unreadable: $sarif_directory" >&2
      exit 1
    fi

    sarif_file_list=$(mktemp "${quality_tmp_root%/}/affected-codeql-files.XXXXXX")
    trap 'rm -f -- "$sarif_file_list"' EXIT
    find "$sarif_directory" -type f -name '*.sarif' -print0 > "$sarif_file_list"

    sarif_files=()
    while IFS= read -r -d '' sarif_file; do
      sarif_files+=("$sarif_file")
    done < "$sarif_file_list"
    if (( ${#sarif_files[@]} == 0 )); then
      echo "CodeQL produced no SARIF files." >&2
      exit 1
    fi

    codeql_findings=0
    for sarif_file in "${sarif_files[@]}"; do
      file_findings=$(jq -er '
        if type == "object" and
           .version == "2.1.0" and
           (.runs | type == "array") and
           (.runs | length > 0) and
           (all(.runs[];
             type == "object" and
             (.tool | type == "object") and
             (.tool.driver | type == "object") and
             ((.tool.driver.name | type) == "string") and
             (.tool.driver.name | length > 0) and
             ((has("results") | not) or (
               (.results | type) == "array" and
               (all(.results[];
                 type == "object" and
                 ((.ruleId | type) == "string") and
                 (.ruleId | length > 0) and
                 (.message | type == "object") and
                 ((.message.text | type) == "string") and
                 (.message.text | length > 0)))))))
        then [.runs[] | .results[]?] | length
        else error("invalid SARIF document")
        end
      ' "$sarif_file") || {
        echo "Invalid CodeQL SARIF: $sarif_file" >&2
        exit 1
      }
      codeql_findings=$((codeql_findings + file_findings))
    done

    if (( codeql_findings > 0 )); then
      printf 'CodeQL reported %d finding(s):\n' "$codeql_findings" >&2
      jq -r '.runs[] | .results[]? | "  \(.ruleId // "unknown"): \(.message.text // "no message")"' \
        "${sarif_files[@]}" >&2
      exit 1
    fi
    ;;
  dependency-graph)
    if (( $# != 2 )); then
      echo "Usage: scripts/quality.sh dependency-graph <snapshot directory>" >&2
      exit 2
    fi
    if ! command -v jq >/dev/null 2>&1; then
      echo "jq is required to validate dependency snapshots." >&2
      exit 127
    fi

    expected_dependency_sha=${EXPECTED_DEPENDENCY_SHA:-}
    expected_dependency_ref=${EXPECTED_DEPENDENCY_REF:-}
    expected_dependency_job=${EXPECTED_DEPENDENCY_JOB:-}
    expected_dependency_correlator=${EXPECTED_DEPENDENCY_CORRELATOR:-}
    if [[ ! "$expected_dependency_sha" =~ ^[0-9a-f]{40}$ ]] ||
      [[ ! "$expected_dependency_ref" =~ ^refs/ ]] ||
      [[ ! "$expected_dependency_job" =~ ^[0-9]+$ ]] ||
      [[ ! "$expected_dependency_correlator" =~ ^[a-z0-9_-]+$ ]]; then
      echo "Expected dependency snapshot identity is missing or invalid." >&2
      exit 1
    fi

    snapshot_directory=$2
    if [[ ! -d "$snapshot_directory" || ! -r "$snapshot_directory" ]]; then
      echo "Dependency snapshot directory is missing or unreadable: $snapshot_directory" >&2
      exit 1
    fi

    snapshot_file_list=$(mktemp "${quality_tmp_root%/}/affected-dependency-files.XXXXXX")
    trap 'rm -f -- "$snapshot_file_list"' EXIT
    find "$snapshot_directory" -type f -name '*.json' -print0 > "$snapshot_file_list"

    snapshot_files=()
    while IFS= read -r -d '' snapshot_file; do
      snapshot_files+=("$snapshot_file")
    done < "$snapshot_file_list"
    if (( ${#snapshot_files[@]} == 0 )); then
      echo "Gradle produced no dependency snapshot." >&2
      exit 1
    fi

    for snapshot_file in "${snapshot_files[@]}"; do
      jq -e \
        --arg sha "$expected_dependency_sha" \
        --arg ref "$expected_dependency_ref" \
        --arg job "$expected_dependency_job" \
        --arg correlator "$expected_dependency_correlator" '
        type == "object" and
        .version == 0 and
        .sha == $sha and
        .ref == $ref and
        (.job | type == "object") and
        .job.id == $job and
        .job.correlator == $correlator and
        (.detector | type == "object") and
        (.detector.name | type == "string" and length > 0) and
        (.detector.version | type == "string" and length > 0) and
        (.detector.url | type == "string" and startswith("https://")) and
        (.scanned | type == "string" and length > 0) and
        (.manifests | type == "object" and length > 0) and
        ([.manifests[].resolved[]] | length > 0) and
        (all(.manifests[];
          type == "object" and
          (.name | type == "string" and length > 0) and
          (.resolved | type == "object") and
          all(.resolved[];
            type == "object" and
            (.package_url | type == "string" and startswith("pkg:maven/")))))
      ' "$snapshot_file" > /dev/null || {
        echo "Invalid or empty dependency snapshot: $snapshot_file" >&2
        exit 1
      }
    done
    ;;
  shell)
    if (( $# != 1 )); then
      echo "Usage: scripts/quality.sh shell" >&2
      exit 2
    fi
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
    if (( $# != 1 )); then
      echo "Usage: scripts/quality.sh workflows" >&2
      exit 2
    fi
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
    echo "Usage: scripts/quality.sh <analyzers|codeql|dependency-graph|shell|workflows> [input directory]" >&2
    exit 2
    ;;
esac
