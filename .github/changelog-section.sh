#!/usr/bin/env bash
# Prints the docs/CHANGELOG.md section for the given version.
# Fails when the section is missing or carries no entries.
set -euo pipefail

version=${1:?usage: changelog-section.sh <version>}
changelog=${2:-docs/CHANGELOG.md}

section=$(awk -v want="## [$version]" '
  index($0, want) == 1 { found = 1; next }
  found && /^## / { exit }
  found { print }
' "$changelog")

if [ -z "$section" ]; then
  echo "docs/CHANGELOG.md has no entries for version $version" >&2
  exit 1
fi

if [ -z "$(printf '%s' "$section" | tr -d '[:space:]')" ]; then
  echo "The $version section in docs/CHANGELOG.md is empty" >&2
  exit 1
fi

printf '%s\n' "$section"
