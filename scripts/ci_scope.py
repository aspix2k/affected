"""Classify a diff so expensive CI runs only when the change can affect it."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

GATES = ("plugin", "health", "codeql", "dependencies")
SHA = re.compile(r"^[0-9a-f]{40}$")
ZERO_SHA = "0" * 40
PRODUCT_PREFIXES = ("src/", "core/", "mcp/", "collector/")
DOC_FILES = frozenset(
    {
        "CHANGELOG.md",
        "CODE_OF_CONDUCT.md",
        "CONTRIBUTING.md",
        "LICENSE",
        "PRIVACY.md",
        "README.md",
        "SECURITY.md",
        "SUPPORT.md",
    }
)
HYGIENE_FILES = frozenset({".gitignore", ".gitattributes"})
SCRIPT_ONLY_FILES = frozenset(
    {
        "scripts/ci_contracts.py",
        "scripts/ci_scope.py",
        "scripts/fixtures.sh",
        "scripts/pitest_gate.py",
        "scripts/quality.sh",
        "scripts/release_currentness.py",
        "scripts/support_matrix.py",
        "scripts/mcp_capabilities.py",
        "scripts/local_gate.py",
        "config/release-currentness.json",
        "config/support-matrix.json",
        "config/mcp-capabilities.json",
    }
)
SAFE_WORKFLOWS = frozenset(
    {
        ".github/workflows/conformance.yml",
        ".github/workflows/mutation.yml",
        ".github/workflows/queue.yml",
        ".github/workflows/release.yml",
        ".github/workflows/dependency-graph.yml",
        ".github/workflows/dependency-graph-submit.yml",
        ".github/dependabot.yml",
    }
)
GRADLE_NAMES = frozenset(
    {
        "build.gradle",
        "build.gradle.kts",
        "gradle.properties",
        "gradlew",
        "gradlew.bat",
        "libs.versions.toml",
        "settings.gradle",
        "settings.gradle.kts",
    }
)
JVM_SUFFIXES = (".java", ".kt", ".kts")


class CiScopeError(RuntimeError):
    """Describe a fail-closed scope classification error."""


def empty_scope() -> dict[str, bool]:
    """Return every expensive gate turned off."""
    return {gate: False for gate in GATES}


def full_scope() -> dict[str, bool]:
    """Return every expensive gate turned on."""
    return {gate: True for gate in GATES}


def normalize(path: str) -> str:
    """Use a POSIX repo-relative path without a leading ./."""
    return path.replace("\\", "/").removeprefix("./")


def merge_scope(left: dict[str, bool], right: dict[str, bool]) -> dict[str, bool]:
    """OR two gate maps together."""
    return {gate: left[gate] or right[gate] for gate in GATES}


def scope_for(paths: list[str]) -> dict[str, bool]:
    """Classify a concrete path list. Unknown or empty input enables every gate."""
    if not paths:
        return full_scope()
    scope = empty_scope()
    for path in paths:
        flags = flags_for(path)
        if flags is None:
            return full_scope()
        scope = merge_scope(scope, flags)
    return scope


def flags_for(path: str) -> dict[str, bool] | None:
    """Return gates for one path, or None when the path is unclassified."""
    path = normalize(path)
    if not path or path.endswith("/"):
        return None
    name = path.rsplit("/", 1)[-1]
    if path in DOC_FILES or path.startswith("docs/") or is_github_doc(path):
        return empty_scope()
    if path in HYGIENE_FILES:
        return empty_scope()
    if path in SCRIPT_ONLY_FILES or path.startswith("scripts/tests/") or path.startswith(".githooks/"):
        return empty_scope()
    if path in SAFE_WORKFLOWS:
        return empty_scope()
    if path == ".github/workflows/ci.yml":
        return {"plugin": True, "health": True, "codeql": False, "dependencies": False}
    if path == ".github/workflows/codeql.yml":
        return {"plugin": False, "health": False, "codeql": True, "dependencies": False}
    if path == ".github/workflows/dependency-review.yml":
        return {"plugin": False, "health": False, "codeql": False, "dependencies": True}
    if path in {
        "scripts/run_gradle.sh",
        "scripts/fetch_gradle.py",
        ".github/changelog-section.sh",
    }:
        return {"plugin": True, "health": True, "codeql": True, "dependencies": False}
    if path == "config/detekt.yml":
        return {"plugin": True, "health": False, "codeql": False, "dependencies": False}
    if is_dependency_path(path, name):
        return full_scope()
    if path.startswith(PRODUCT_PREFIXES):
        if path.endswith(JVM_SUFFIXES):
            return {"plugin": True, "health": False, "codeql": True, "dependencies": False}
        return {"plugin": True, "health": False, "codeql": False, "dependencies": False}
    if path.startswith(("conformance/", "fixtures/")):
        return {"plugin": True, "health": False, "codeql": False, "dependencies": False}
    return None


def is_github_doc(path: str) -> bool:
    """Return whether a .github path is documentation or an issue template."""
    if path == ".github/CODEOWNERS":
        return True
    return path.startswith((".github/ISSUE_TEMPLATE/", ".github/PULL_REQUEST_TEMPLATE")) or (
        path.startswith(".github/") and path.endswith(".md")
    )


def is_dependency_path(path: str, name: str) -> bool:
    """Return whether the path can change resolved dependencies."""
    return (
        name in GRADLE_NAMES
        or path.startswith("gradle/")
        or path.endswith(".lockfile")
        or path == ".github/dependabot.yml"
    )


def event_range(event_name: str, event: dict) -> tuple[str, str] | None:
    """Read the git range for a GitHub event, or None when it is unusable."""
    if event_name == "pull_request":
        pull = event.get("pull_request") or {}
        base = str((pull.get("base") or {}).get("sha") or "")
        head = str((pull.get("head") or {}).get("sha") or "")
    elif event_name == "merge_group":
        group = event.get("merge_group") or {}
        base = str(group.get("base_sha") or "")
        head = str(group.get("head_sha") or "")
    elif event_name == "push":
        base = str(event.get("before") or "")
        head = str(event.get("after") or "")
    else:
        return None
    if not SHA.fullmatch(base) or not SHA.fullmatch(head) or base == ZERO_SHA:
        return None
    return base, head


def git_changed_files(root: Path, base: str, head: str) -> list[str]:
    """List paths changed between two commits. Missing objects raise CiScopeError."""
    ensure_commit(root, base)
    ensure_commit(root, head)
    result = subprocess.run(
        ["git", "-C", str(root), "diff", "--name-only", "-z", f"{base}...{head}"],
        check=False,
        capture_output=True,
    )
    if result.returncode != 0:
        raise CiScopeError(result.stderr.decode("utf-8", errors="replace").strip() or "git diff failed")
    return [path for path in result.stdout.decode("utf-8").split("\0") if path]


def ensure_commit(root: Path, sha: str) -> None:
    """Fetch a commit if the local clone does not have it yet."""
    probe = subprocess.run(
        ["git", "-C", str(root), "cat-file", "-e", f"{sha}^{{commit}}"],
        check=False,
        capture_output=True,
    )
    if probe.returncode == 0:
        return
    fetched = subprocess.run(
        ["git", "-C", str(root), "fetch", "--no-tags", "--depth=1", "origin", sha],
        check=False,
        capture_output=True,
    )
    if fetched.returncode != 0:
        raise CiScopeError(f"Cannot resolve commit {sha}")


def detect_scope(root: Path) -> dict[str, bool]:
    """Classify the current GitHub event, or enable every gate when detection fails."""
    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    event_path = os.environ.get("GITHUB_EVENT_PATH", "")
    if not event_name or not event_path:
        return full_scope()
    try:
        event = json.loads(Path(event_path).read_text(encoding="utf-8"))
        if not isinstance(event, dict):
            return full_scope()
        rang = event_range(event_name, event)
        if rang is None:
            return full_scope()
        return scope_for(git_changed_files(root, *rang))
    except (OSError, json.JSONDecodeError, CiScopeError):
        return full_scope()


def write_github_output(path: Path, scope: dict[str, bool]) -> None:
    """Write GitHub Actions outputs as lowercase booleans."""
    body = "".join(f"{gate}={'true' if scope[gate] else 'false'}\n" for gate in GATES)
    path.write_text(path.read_text(encoding="utf-8") + body if path.exists() else body, encoding="utf-8")


def main(arguments: list[str] | None = None) -> int:
    """Print or export the expensive-gate scope for a path list or GitHub event."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--paths", nargs="*", help="Classify these repo-relative paths")
    parser.add_argument("--github-output", type=Path, help="Append plugin=true style outputs")
    parser.add_argument("--json", action="store_true", help="Print the scope as JSON")
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="Repository root for git detection")
    options = parser.parse_args(arguments)
    scope = scope_for(options.paths) if options.paths is not None else detect_scope(options.root)
    if options.github_output is not None:
        write_github_output(options.github_output, scope)
    if options.json:
        print(json.dumps(scope, sort_keys=True))
    else:
        enabled = [gate for gate in GATES if scope[gate]]
        print("Expensive CI gates: " + (", ".join(enabled) if enabled else "none"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
