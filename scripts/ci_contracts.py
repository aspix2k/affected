"""Fail when pull-request CI loses a required gate or splits one Gradle graph."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CI = ROOT / ".github/workflows/ci.yml"
CONFORMANCE = ROOT / ".github/workflows/conformance.yml"
CODEQL = ROOT / ".github/workflows/codeql.yml"
MUTATION = ROOT / ".github/workflows/mutation.yml"
QUALITY = ROOT / ".github/workflows/quality.yml"
GRADLE_ACTION = re.compile(r"uses:\s*gradle/actions/[^\s@]+@([0-9a-f]{40})")
PLUGIN_TASKS = (
    "detekt",
    "test",
    "koverXmlReport",
    "koverVerify",
    "buildPlugin",
    "verifyPlugin",
    ":collector:spotbugsMain",
    ":collector:spotbugsMaven",
)


class CiContractError(RuntimeError):
    """Describe a fail-closed CI contract violation."""


def read(path: Path) -> str:
    """Read a tracked workflow file as UTF-8 text."""
    if not path.is_file() or path.is_symlink():
        raise CiContractError(f"Missing workflow: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def check(root: Path = ROOT) -> None:
    """Validate the required CI shape without weakening any gate."""
    ci = read(root / ".github/workflows/ci.yml")
    conformance = read(root / ".github/workflows/conformance.yml")
    codeql = read(root / ".github/workflows/codeql.yml")
    mutation = read(root / ".github/workflows/mutation.yml")
    quality = root / ".github/workflows/quality.yml"
    if quality.exists():
        raise CiContractError("quality.yml must be folded into ci.yml")

    if "needs:" not in ci or "if: ${{ always() && !cancelled() }}" not in ci:
        raise CiContractError("verify must aggregate required jobs even when one failed")
    if not re.search(r"^  verify:\n(?:.*\n)*?    needs:", ci, re.MULTILINE):
        raise CiContractError("The required verify job must depend on every fast gate")

    plugin = slice_job(ci, "plugin")
    if plugin.count("scripts/run_gradle.sh") != 1 or "./gradlew" in plugin:
        raise CiContractError("The plugin job must start Gradle exactly once through scripts/run_gradle.sh")
    for task in PLUGIN_TASKS:
        if task not in plugin:
            raise CiContractError(f"The plugin job must keep {task}")
    if "printVersion" not in plugin and "changelog-section.sh" not in plugin:
        raise CiContractError("The plugin job must still enforce the changelog section")

    scripts = slice_job(ci, "scripts")
    for token in (
        "scripts/quality.sh shell",
        "scripts/quality.sh workflows",
        "scripts/quality.sh analyzers",
        "scripts.tests.test_release_currentness",
        "scripts/release_currentness.py",
        "scripts.tests.test_support_matrix",
        "scripts/support_matrix.py --check",
        "scripts.tests.test_ci_contracts",
        "scripts.tests.test_run_gradle",
        "SHELLCHECK_VERSION",
        "ACTIONLINT_VERSION",
    ):
        if token not in scripts:
            raise CiContractError(f"The scripts job must keep {token}")

    if "buildHealth" not in slice_job(ci, "health"):
        raise CiContractError("buildHealth must remain a required CI job")

    if '- "README.md"' in conformance:
        raise CiContractError("README edits must not start the exact-impact matrix")
    if "CLI contracts" in conformance or ":core:test \\\n            --rerun-tasks" in conformance:
        raise CiContractError("Do not rerun the full core suite as fake CLI contracts")
    if "-Paffected.cliConformance=true" not in conformance:
        raise CiContractError("Native CLI fixtures must keep the conformance flag")
    if "CrossPlatformPathTest" not in conformance:
        raise CiContractError("macOS and Windows must still run CrossPlatformPathTest")

    pins = GRADLE_ACTION.findall(ci + conformance + codeql + mutation)
    if not pins or len(set(pins)) != 1:
        raise CiContractError(f"gradle/actions must use one SHA, found {sorted(set(pins))}")

    if "scripts/pitest_gate.py" not in mutation:
        raise CiContractError("Weekly mutation must fail on surviving mutants")


def slice_job(workflow: str, name: str) -> str:
    """Return one top-level GitHub Actions job block."""
    match = re.search(rf"(?ms)^  {re.escape(name)}:\n(.*?)(?=^  [A-Za-z0-9_-]+:|\Z)", workflow)
    if match is None:
        raise CiContractError(f"Missing job: {name}")
    return match.group(0)


def main(arguments: list[str] | None = None) -> int:
    """Run the CI contract check with a concise error."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", required=True)
    parser.parse_args(arguments)
    try:
        check()
    except CiContractError as error:
        print(f"CI contract error: {error}", file=sys.stderr)
        return 1
    print("CI contracts are intact.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
