"""Fail locally with the cheap CI gates before a commit or push is published."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from collections.abc import Callable, Sequence
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT_TESTS = (
    "scripts.tests.test_ci_contracts",
    "scripts.tests.test_ci_scope",
    "scripts.tests.test_fetch_gradle",
    "scripts.tests.test_local_gate",
    "scripts.tests.test_mcp_capabilities",
    "scripts.tests.test_pitest_gate",
    "scripts.tests.test_release_currentness",
    "scripts.tests.test_run_gradle",
    "scripts.tests.test_support_matrix",
)
Runner = Callable[..., subprocess.CompletedProcess[str]]


class LocalGateError(RuntimeError):
    """Describe a failed local commit or push gate."""


def checks_for(mode: str) -> list[tuple[str, list[str], int]]:
    """Return named commands and timeouts for a hook mode."""
    python = sys.executable
    commit = [
        ("detekt", [str(ROOT / "scripts" / "run_gradle.sh"), "detekt"], 180),
        ("script tests", [python, "-m", "unittest", *SCRIPT_TESTS], 120),
        ("CI contracts", [python, str(ROOT / "scripts" / "ci_contracts.py"), "--check"], 30),
        ("analyzer policy", [str(ROOT / "scripts" / "quality.sh"), "analyzers"], 30),
    ]
    if mode == "commit":
        return commit
    if mode == "push":
        return [
            *commit,
            ("shell", [str(ROOT / "scripts" / "quality.sh"), "shell"], 60),
        ]
    raise LocalGateError(f"Unknown local gate mode: {mode}")


def run(mode: str, *, runner: Runner | None = None, root: Path = ROOT) -> None:
    """Execute every check for the mode and stop on the first failure."""
    if mode not in {"commit", "push"}:
        raise LocalGateError(f"Unknown local gate mode: {mode}")
    if not (root / "gradlew").is_file() or (root / "gradlew").is_symlink():
        raise LocalGateError("Gradle wrapper is missing")
    if mode == "push" and shutil.which("shellcheck") is None:
        raise LocalGateError("shellcheck is required for pre-push")
    execute = runner or _run_command
    for name, command, timeout in checks_for(mode):
        try:
            completed = execute(
                command,
                cwd=str(root),
                check=False,
                capture_output=True,
                text=True,
                timeout=timeout,
            )
        except subprocess.TimeoutExpired as error:
            raise LocalGateError(f"{name} timed out after {timeout}s") from error
        if completed.returncode != 0:
            detail = (completed.stderr or completed.stdout or "").strip()
            suffix = f": {detail}" if detail else ""
            raise LocalGateError(f"{name} failed{suffix}")


def install(root: Path = ROOT) -> None:
    """Point this clone at the tracked hook directory."""
    commit = root / ".githooks" / "pre-commit"
    push = root / ".githooks" / "pre-push"
    if not commit.is_file() or commit.is_symlink() or not push.is_file() or push.is_symlink():
        raise LocalGateError("Tracked git hooks are missing")
    completed = subprocess.run(
        ["git", "-C", str(root), "config", "--local", "core.hooksPath", ".githooks"],
        check=False,
        capture_output=True,
        text=True,
        timeout=10,
    )
    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout or "").strip()
        raise LocalGateError(detail or "Unable to set core.hooksPath")


def _run_command(command: Sequence[str], **kwargs: object) -> subprocess.CompletedProcess[str]:
    """Run one gate command without a shell."""
    return subprocess.run(list(command), **kwargs)  # type: ignore[arg-type]


def main(arguments: list[str] | None = None) -> int:
    """Install hooks or run a commit/push gate."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("commit", "push", "install"))
    options = parser.parse_args(arguments)
    try:
        if options.mode == "install":
            install()
            print("Git hooks path set to .githooks")
        else:
            run(options.mode)
            print(f"Local {options.mode} gate passed.")
    except LocalGateError as error:
        print(f"Local gate error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
