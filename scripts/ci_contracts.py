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
    for filename in ("dependabot.yml", "dependabot.yaml"):
        dependabot = root / ".github" / filename
        if dependabot.exists() or dependabot.is_symlink():
            raise CiContractError("Dependabot version-update pull requests must remain disabled")

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
        "scripts.tests.test_mcp_capabilities",
        "scripts/mcp_capabilities.py --check",
        "scripts.tests.test_ci_contracts",
        "scripts.tests.test_ci_scope",
        "scripts.tests.test_fetch_gradle",
        "scripts.tests.test_run_gradle",
        "changelog-section.sh",
        "SHELLCHECK_VERSION",
        "ACTIONLINT_VERSION",
    ):
        if token not in scripts:
            raise CiContractError(f"The scripts job must keep {token}")

    if "buildHealth" not in slice_job(ci, "health"):
        raise CiContractError("buildHealth must remain a required CI job")

    check_scope(root, ci, codeql)

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
    if "cache-redirector.jetbrains.com/repo1.maven.org/maven2" not in read(root / "settings.gradle.kts"):
        raise CiContractError("Plugin resolution must prefer the JetBrains Maven Central mirror")
    if "AFFECTED_PREFER_MAVEN_CENTRAL" not in read(root / "settings.gradle.kts"):
        raise CiContractError("Gradle must be able to prefer Maven Central after a cache-redirector 5xx")
    if "actions/cache@" not in read(root / ".github/workflows/dependency-graph.yml"):
        raise CiContractError("Dependency graph must cache the Gradle wrapper distribution")
    submit = read(root / ".github/workflows/dependency-graph-submit.yml")
    if "push|workflow_dispatch" not in submit or "refs/heads/main" not in submit:
        raise CiContractError("Submit must accept main workflow_dispatch snapshots")
    if 'add("intellijPlatformDependencies", enforcedPlatform("com.fasterxml.jackson:jackson-bom:' not in read(
        root / "mcp/build.gradle.kts"
    ):
        raise CiContractError("The MCP module must enforce the patched Jackson BOM")

    check_merge_queue(root, ci, codeql)
    check_wrapper(root)


def check_scope(root: Path, ci: str, codeql: str) -> None:
    """Required checks must always report; expensive work follows ci_scope."""
    review = read(root / ".github/workflows/dependency-review.yml")
    graph = read(root / ".github/workflows/dependency-graph.yml")
    if not (root / "scripts/ci_scope.py").is_file():
        raise CiContractError("ci_scope.py is missing")
    if 'if: needs.scope.outputs.plugin == \'true\'' not in slice_job(ci, "plugin"):
        raise CiContractError("plugin must run only when ci_scope asks for it")
    if 'if: needs.scope.outputs.health == \'true\'' not in slice_job(ci, "health"):
        raise CiContractError("health must run only when ci_scope asks for it")
    verify = slice_job(ci, "verify")
    if "PLUGIN_REQUIRED" not in verify or "skipped" not in verify:
        raise CiContractError("verify must accept a scoped skip and fail a required skip")
    if "needs.scope.result" not in verify:
        raise CiContractError("verify must fail when scope classification fails")
    if "paths:" in (has_on_block(ci) or ""):
        raise CiContractError("ci.yml must not path-filter required checks")
    if "scripts/ci_scope.py" not in codeql or "steps.scope.outputs.codeql" not in codeql:
        raise CiContractError("CodeQL pull-request must keep its check name and skip only the analyze")
    if "scripts/ci_scope.py" not in review or "steps.scope.outputs.dependencies" not in review:
        raise CiContractError("Dependency review must keep its check name and skip only the compare")
    if "scripts/ci_scope.py" not in graph or "needs.scope.outputs.dependencies" not in graph:
        raise CiContractError("Dependency graph generate must follow ci_scope")


def has_on_block(workflow: str) -> str:
    """Return the top-level on: block of a workflow."""
    match = re.search(r"(?ms)^on:\n(.*?)(?=^[A-Za-z]|\Z)", workflow)
    return match.group(1) if match else ""


def check_merge_queue(root: Path, ci: str, codeql: str) -> None:
    """Required checks must report on merge_group or the merge queue hangs."""
    review = read(root / ".github/workflows/dependency-review.yml")
    queue = read(root / ".github/workflows/queue.yml")
    for name, text in (
        ("ci.yml", ci),
        ("codeql.yml", codeql),
        ("dependency-review.yml", review),
    ):
        if not has_on_trigger(text, "merge_group"):
            raise CiContractError(f"{name} must trigger on merge_group so the merge queue can report required checks")

    if "github.event_name == 'pull_request' || github.event_name == 'merge_group'" not in codeql:
        raise CiContractError("CodeQL pull-request must analyze merge_group")
    if "github.event_name != 'pull_request'" in codeql:
        raise CiContractError("CodeQL main must not run on merge_group")
    if "github.event.merge_group.base_sha" not in review or "github.event.merge_group.head_sha" not in review:
        raise CiContractError("Dependency review must compare merge_group SHAs")
    if "gh pr merge" not in queue or "--squash" not in queue:
        raise CiContractError("queue.yml must enable squash auto-merge")
    if "--auto" not in queue or re.search(r"gh pr merge(?![^\n]*--auto)", queue):
        raise CiContractError("queue.yml must never merge without --auto")


def has_on_trigger(workflow: str, name: str) -> bool:
    """Return whether a workflow listens for a top-level event."""
    match = re.search(r"(?ms)^on:\n(.*?)(?=^[A-Za-z]|\Z)", workflow)
    return bool(match and re.search(rf"(?m)^  {re.escape(name)}:\s*$", match.group(1)))


def check_wrapper(root: Path) -> None:
    """Keep the wrapper checksum and refuse a one-shot 10-second download."""
    text = read(root / "gradle/wrapper/gradle-wrapper.properties")
    timeout = wrapper_int(text, "networkTimeout")
    retries = wrapper_int(text, "retries")
    if timeout < 60_000:
        raise CiContractError(f"Gradle wrapper networkTimeout must be at least 60s, found {timeout}")
    if retries < 3:
        raise CiContractError(f"Gradle wrapper retries must be at least 3, found {retries}")
    if "distributionSha256Sum=" not in text:
        raise CiContractError("Gradle wrapper must keep distributionSha256Sum")
    runner = read(root / "scripts/run_gradle.sh")
    if "scripts/fetch_gradle.py" not in runner and "fetch_gradle.py" not in runner:
        raise CiContractError("run_gradle.sh must seed the wrapper cache before starting Gradle")
    if "Received status code" not in runner or "AFFECTED_PREFER_MAVEN_CENTRAL" not in runner:
        raise CiContractError("run_gradle.sh must retry cache-redirector 5xx with Maven Central first")


def wrapper_int(text: str, name: str) -> int:
    """Read one integer Gradle wrapper property."""
    match = re.search(rf"(?m)^{re.escape(name)}=(\d+)$", text)
    if match is None:
        raise CiContractError(f"Gradle wrapper is missing {name}")
    return int(match.group(1))


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
