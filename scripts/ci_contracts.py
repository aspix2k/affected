"""Fail when pull-request CI loses a required gate or splits one Gradle graph."""

from __future__ import annotations

import argparse
import hashlib
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
EXACT_IMPACT_JOBS = (
    "scope",
    "exact-impact",
    "cross-platform-paths",
    "cli-native",
    "dotnet-sdks",
    "phpunit-versions",
)
CODEQL_KOTLIN_COMPAT_SHA256 = "d934807f5c469f8f1d494a53ea54de114b4c8f95e7bd370868065eefc035fa73"
CODEQL_KOTLIN_PROBE_SHA256 = "c2466a058bb8191dce7c14619d13ba6c2e6341ef4a01417fc746bac5a73458ab"


class CiContractError(RuntimeError):
    """Describe a fail-closed CI contract violation."""


def read(path: Path) -> str:
    """Read a tracked workflow file as UTF-8 text."""
    if not path.is_file() or path.is_symlink():
        raise CiContractError(f"Missing workflow: {path.name}")
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

    verify = slice_job(ci, "verify")
    if not re.search(r"(?m)^    name: verify$", verify):
        raise CiContractError("The required verify context name must remain stable")
    if not has_line(verify, "if: ${{ always() }}"):
        raise CiContractError("verify must always aggregate required jobs")
    if not re.search(r"^  verify:\n(?:.*\n)*?    needs:", ci, re.MULTILINE):
        raise CiContractError("The required verify job must depend on every fast gate")
    if re.search(r"(?m)^  product-verifier:$", ci) is None or "product-verifier" not in verify:
        raise CiContractError("The product verifier matrix must remain a required gate")

    plugin = slice_job(ci, "plugin")
    if plugin.count("scripts/run_gradle.sh") != 1 or "./gradlew" in plugin:
        raise CiContractError("The plugin job must start Gradle exactly once through scripts/run_gradle.sh")
    for task in PLUGIN_TASKS:
        if task not in plugin:
            raise CiContractError(f"The plugin job must keep {task}")
    if "printVersion" not in plugin and "changelog-section.sh" not in plugin:
        raise CiContractError("The plugin job must still enforce the changelog section")
    check_product_verifier(ci)

    scripts = slice_job(ci, "scripts")
    for token in (
        "scripts/quality.sh shell",
        "scripts/quality.sh workflows",
        "scripts/quality.sh analyzers",
        "scripts.tests.test_release_currentness",
        "scripts/release_currentness.py",
        "GH_TOKEN",
        "scripts.tests.test_support_matrix",
        "scripts/support_matrix.py --check",
        "scripts.tests.test_mcp_capabilities",
        "scripts/mcp_capabilities.py --check",
        "scripts.tests.test_changelog_fragments",
        "scripts.tests.test_ci_contracts",
        "scripts.tests.test_ci_scope",
        "scripts.tests.test_codeql_kotlin_compat_probe",
        "scripts.tests.test_docs_layout",
        "scripts.tests.test_fetch_gradle",
        "scripts.tests.test_local_gate",
        "scripts.tests.test_plugin_verifier_reports",
        "scripts.tests.test_product_claims",
        "scripts.tests.test_run_gradle",
        "changelog_fragments.py check",
        "changelog-section.sh",
        "SHELLCHECK_VERSION",
        "ACTIONLINT_VERSION",
    ):
        if token not in scripts:
            raise CiContractError(f"The scripts job must keep {token}")

    if "scripts/local_gate.py commit" not in read(root / ".githooks/pre-commit"):
        raise CiContractError("pre-commit must run the local commit gate")
    if "scripts/local_gate.py push" not in read(root / ".githooks/pre-push"):
        raise CiContractError("pre-push must run the local push gate")
    contributing = read(root / "docs/CONTRIBUTING.md")
    if "core.hooksPath" not in contributing or "scripts/local_gate.py install" not in contributing:
        raise CiContractError("CONTRIBUTING must document hook installation")
    if "docs/changelog.d" not in contributing or "docs/CHANGELOG.md" not in contributing:
        raise CiContractError("CONTRIBUTING must tell pull requests to use changelog fragments")
    if "required checks `verify` and `exact-impact`" not in contributing:
        raise CiContractError("CONTRIBUTING must document every required aggregate check")

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
    check_conformance(conformance)

    pins = GRADLE_ACTION.findall(ci + conformance + codeql + mutation)
    if not pins or len(set(pins)) != 1:
        raise CiContractError(f"gradle/actions must use one SHA, found {sorted(set(pins))}")

    if "scripts/pitest_gate.py" not in mutation:
        raise CiContractError("Weekly mutation must fail on surviving mutants")
    if ":core:pitest" not in mutation or "core/build/reports/pitest/mutations.xml" not in mutation:
        raise CiContractError("Weekly mutation must run and gate the core PIT report")
    core_build = read(root / "core/build.gradle.kts")
    if "ExecutablePathKt*" not in core_build:
        raise CiContractError("Weekly mutation must keep ExecutablePath in the core PIT target")
    currentness = read(root / "scripts/release_currentness.py")
    if "cache-redirector.jetbrains.com/repo1.maven.org/maven2" not in currentness:
        raise CiContractError("release_currentness must read Maven metadata through cache-redirector first")
    if currentness.find("cache-redirector.jetbrains.com/repo1.maven.org/maven2") > currentness.find(
        "https://repo.maven.apache.org/maven2"
    ):
        raise CiContractError("release_currentness must prefer cache-redirector before repo.maven.apache.org")
    if "cache-redirector.jetbrains.com/repo1.maven.org/maven2" not in read(root / "settings.gradle.kts"):
        raise CiContractError("Plugin resolution must prefer the JetBrains Maven Central mirror")
    if "AFFECTED_PREFER_MAVEN_CENTRAL" not in read(root / "settings.gradle.kts"):
        raise CiContractError("Gradle must be able to prefer Maven Central after a cache-redirector 5xx")
    check_codeql_compatibility(root, codeql)
    if "actions/cache@" not in read(root / ".github/workflows/dependency-graph.yml"):
        raise CiContractError("Dependency graph must cache the Gradle wrapper distribution")
    submit = read(root / ".github/workflows/dependency-graph-submit.yml")
    if "push|workflow_dispatch" not in submit or "refs/heads/main" not in submit:
        raise CiContractError("Submit must accept main workflow_dispatch snapshots")
    if (
        "github.event.workflow_run.event == 'push'" not in submit
        or "github.event.workflow_run.event == 'workflow_dispatch'" not in submit
        or "refs/pull/" in submit
    ):
        raise CiContractError("Submit must not run for pull-request graphs")
    if 'add("intellijPlatformDependencies", enforcedPlatform("com.fasterxml.jackson:jackson-bom:' not in read(
        root / "mcp/build.gradle.kts"
    ):
        raise CiContractError("The MCP module must enforce the patched Jackson BOM")
    root_build = read(root / "build.gradle.kts")
    if 'kover(project(":core"))' not in root_build or 'kover(project(":mcp"))' not in root_build:
        raise CiContractError("Kover must verify :core and :mcp, not only the root plugin sources")
    bound = re.search(r"minBound\((\d+)\)", root_build)
    if bound is None or int(bound.group(1)) < 60:
        raise CiContractError("Kover line floor must stay at least 60")

    check_merge_queue(root, ci, codeql, conformance)
    check_wrapper(root)


def check_codeql_compatibility(
    root: Path,
    codeql: str,
) -> None:
    """Keep the temporary Kotlin compiler shim isolated to complete CodeQL builds."""
    relative = "scripts/codeql-kotlin-compat.init.gradle"
    probe_relative = "scripts/codeql_kotlin_compat_probe.py"
    compatibility = read(root / relative)
    digest = hashlib.sha256(compatibility.encode()).hexdigest()
    if digest != CODEQL_KOTLIN_COMPAT_SHA256:
        raise CiContractError("The CodeQL Kotlin compatibility shim changed without review")
    probe = read(root / probe_relative)
    probe_digest = hashlib.sha256(probe.encode()).hexdigest()
    if probe_digest != CODEQL_KOTLIN_PROBE_SHA256:
        raise CiContractError("The CodeQL Kotlin compatibility probe changed without review")
    compatibility_tokens = (
        relative,
        "-Paffected.codeql.kotlinPluginVersion=2.4.10",
    )
    for workflow_relative, workflow in workflow_inventory(root).items():
        if workflow_relative == ".github/workflows/codeql.yml":
            continue
        if any(token in workflow for token in compatibility_tokens):
            raise CiContractError(
                f"The CodeQL Kotlin compatibility shim must stay analysis-only: {workflow_relative}"
            )
    runner = read(root / "scripts/run_gradle.sh")
    if any(token in runner for token in compatibility_tokens):
        raise CiContractError(
            "The analysis-only CodeQL Kotlin compatibility shim must stay out of the shared Gradle runner"
        )
    local_gate = read(root / "scripts/local_gate.py")
    if any(token in local_gate for token in compatibility_tokens):
        raise CiContractError(
            "The analysis-only CodeQL Kotlin compatibility shim must stay out of local product gates"
        )
    for job_name in ("pull-request", "main"):
        job = slice_job(codeql, job_name)
        if job.count(f"--init-script {relative}") != 1 or job.count(
            "-Paffected.codeql.kotlinPluginVersion=2.4.10"
        ) != 1:
            raise CiContractError(
                f"CodeQL {job_name} must use the Kotlin compatibility shim exactly once"
            )
        if job.count("--no-build-cache") != 1 or "--build-cache" in job:
            raise CiContractError(
                f"CodeQL {job_name} Kotlin compatibility must disable the build cache"
            )
        probe_invocation = f"run: python3 {probe_relative}"
        if job.count(probe_invocation) != 1 or job.find(probe_invocation) > job.find(
            "github/codeql-action/init@"
        ):
            raise CiContractError(
                f"CodeQL {job_name} must run the Kotlin compatibility behavioral probe before init"
            )
        for token in (
            "languages: java-kotlin",
            "build-mode: manual",
            "clean classes :core:classes :mcp:classes :collector:classes "
            ":collector:mavenClasses",
        ):
            if token not in job:
                raise CiContractError(
                    f"CodeQL {job_name} Kotlin compatibility must keep {token}"
            )


def check_product_verifier(ci: str) -> None:
    """Keep every product cell bound to one promoted archive and validated report."""
    scope = slice_job(ci, "scope")
    job = slice_job(ci, "product-verifier")
    for line in (
        "verifier: ${{ steps.verifier.outputs.matrix }}",
        "matrix=$(python3 scripts/support_matrix.py --verifier-matrix)",
    ):
        if not has_line(scope, line):
            raise CiContractError("The product verifier matrix must come from support_matrix.py")
    for line in (
        "needs: [scope, plugin]",
        "if: needs.scope.outputs.plugin == 'true'",
        "timeout-minutes: 30",
        "fail-fast: false",
        "max-parallel: 4",
        "include: ${{ fromJSON(needs.scope.outputs.verifier) }}",
        "verifyPlugin -x buildPlugin",
        "SOURCE_COMMIT: ${{ github.event.pull_request.head.sha || github.sha }}",
        '--commit "$SOURCE_COMMIT" \\',
    ):
        if not has_line(job, line):
            raise CiContractError(f"The product verifier must keep {line}")
    required_tokens = (
        "actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c",
        "name: plugin",
        "path: build/verifier-input",
        "python3 scripts/plugin_verifier_reports.py artifact",
        "git rev-parse 'HEAD^{tree}'",
        '"-Paffected.verifier.type=${{ matrix.type }}"',
        '"-Paffected.verifier.version=${{ matrix.version }}"',
        '"-Paffected.verifier.archive=${{ steps.artifact.outputs.archive }}"',
        "python3 scripts/plugin_verifier_reports.py report",
        '"--code=${{ matrix.code }}"',
        '"--build=${{ matrix.build }}"',
        '"--gradle=${{ matrix.gradle }}"',
        '"--maven=${{ matrix.maven }}"',
        "if: always()",
        "name: product-verifier-${{ matrix.product }}-${{ matrix.endpoint }}",
        "path: build/reports/pluginVerifier",
    )
    for token in required_tokens:
        if job.count(token) != 1:
            raise CiContractError(f"The product verifier must keep exactly one {token}")
    if job.count("scripts/run_gradle.sh") != 1 or job.count("verifyPlugin") != 1:
        raise CiContractError("The product verifier must run one isolated verifier graph")


def workflow_inventory(root: Path) -> dict[str, str]:
    """Read a bounded inventory of every GitHub Actions workflow."""
    directory = root / ".github/workflows"
    if not directory.is_dir() or directory.is_symlink():
        raise CiContractError("Missing workflow directory")
    paths: list[Path] = []
    entries = 0
    for path in directory.iterdir():
        entries += 1
        if entries > 64:
            raise CiContractError("Too many workflow directory entries to validate safely")
        if path.suffix not in {".yml", ".yaml"}:
            continue
        paths.append(path)
    return {
        path.relative_to(root).as_posix(): read(path)
        for path in sorted(paths, key=lambda candidate: candidate.name)
    }


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
    if 'if [ "$required" = false ]' not in verify or "skipped" not in verify:
        raise CiContractError("verify must accept a scoped skip and fail a required skip")
    verify_bindings = (
        (
            "scope",
            "SCOPE_RESULT",
            "needs.scope.result",
            'require_success scope "$SCOPE_RESULT"',
        ),
        (
            "scripts",
            "SCRIPT_RESULT",
            "needs.scripts.result",
            'require_success scripts "$SCRIPT_RESULT"',
        ),
        (
            "plugin",
            "PLUGIN_RESULT",
            "needs.plugin.result",
            'require_when plugin "${PLUGIN_REQUIRED:-true}" "$PLUGIN_RESULT"',
        ),
        (
            "product-verifier",
            "PRODUCT_VERIFIER_RESULT",
            "needs.product-verifier.result",
            'require_when product-verifier "${PLUGIN_REQUIRED:-true}" "$PRODUCT_VERIFIER_RESULT"',
        ),
        (
            "health",
            "HEALTH_RESULT",
            "needs.health.result",
            'require_when health "${HEALTH_REQUIRED:-true}" "$HEALTH_RESULT"',
        ),
    )
    for name, variable, source, invocation in verify_bindings:
        if not has_line(verify, f"{variable}: ${{{{ {source} }}}}") or not has_line(
            verify,
            invocation,
        ):
            raise CiContractError(f"verify must bind and check the {name} result")
    for variable, source in (
        ("PLUGIN_REQUIRED", "needs.scope.outputs.plugin"),
        ("HEALTH_REQUIRED", "needs.scope.outputs.health"),
    ):
        if not has_line(verify, f"{variable}: ${{{{ {source} }}}}"):
            raise CiContractError(f"verify must bind {source}")
    if "paths:" in (has_on_block(ci) or ""):
        raise CiContractError("ci.yml must not path-filter required checks")
    if "scripts/ci_scope.py" not in codeql or "steps.scope.outputs.codeql" not in codeql:
        raise CiContractError("CodeQL pull-request must keep its check name and skip only the analyze")
    if "scripts/ci_scope.py" not in review or "steps.scope.outputs.dependencies" not in review:
        raise CiContractError("Dependency review must keep its check name and skip only the compare")
    if "dependency-graph/compare/" in review:
        raise CiContractError(
            "PR review must not call the compare API; submit never publishes PR-head snapshots"
        )
    if "scripts/ci_scope.py" not in graph or "needs.scope.outputs.dependencies" not in graph:
        raise CiContractError("Dependency graph generate must follow ci_scope")
    if "Require a complete dependency snapshot" not in graph:
        raise CiContractError("Generate must require a complete snapshot artifact")


def check_conformance(conformance: str) -> None:
    """Require one fail-closed aggregate for every exact-impact lane."""
    if "paths:" in (has_on_block(conformance) or ""):
        raise CiContractError(
            "conformance.yml must report its required aggregate on every pull request"
        )
    scope = slice_job(conformance, "scope")
    classifier = re.search(
        r'(?m)^      - id: classify\n        run: python3 scripts/ci_scope\.py --github-output "\$GITHUB_OUTPUT"$',
        scope,
    )
    if not has_line(scope, "exact: ${{ steps.classify.outputs.exact }}") or classifier is None:
        raise CiContractError("conformance scope must publish the classifier exact-impact decision")
    cli_native = slice_job(conformance, "cli-native")
    adapter_directory = "core/src/main/python"
    for command in (
        f"python -m ruff check {adapter_directory}",
        f"python -m ruff format --check {adapter_directory}",
    ):
        if cli_native.count(command) != 1 or not has_line(cli_native, command):
            raise CiContractError("Native CLI must lint all bundled Python adapters")
    for name in EXACT_IMPACT_JOBS[1:]:
        job = slice_job(conformance, name)
        if (
            "needs: [scope]" not in job
            or "needs.scope.outputs.exact == 'true'" not in job
        ):
            raise CiContractError(f"{name} must follow the exact-impact scope")
    required = slice_job(conformance, "required")
    if not re.search(r"(?m)^    name: exact-impact$", required):
        raise CiContractError("The required exact-impact context name must remain stable")
    expected_needs = f"needs: [{', '.join(EXACT_IMPACT_JOBS)}]"
    if expected_needs not in required:
        raise CiContractError("The required aggregate must depend on every exact-impact lane")
    if "if: ${{ always() }}" not in required:
        raise CiContractError("The required exact-impact aggregate must always report")
    if 'if [ "$required" = false ]' not in required or "skipped" not in required:
        raise CiContractError(
            "The required exact-impact aggregate must fail a skipped required lane"
        )
    if not has_line(required, "SCOPE_RESULT: ${{ needs.scope.result }}") or not has_line(
        required,
        'require_success scope "$SCOPE_RESULT"',
    ):
        raise CiContractError("The required exact-impact aggregate must bind and check scope")
    if not has_line(required, "EXACT_REQUIRED: ${{ needs.scope.outputs.exact }}"):
        raise CiContractError("The required exact-impact aggregate must bind the exact scope")
    result_bindings = (
        ("exact-impact", "EXACT_RESULT", "needs.exact-impact.result"),
        ("cross-platform-paths", "PATHS_RESULT", "needs.cross-platform-paths.result"),
        ("cli-native", "CLI_RESULT", "needs.cli-native.result"),
        ("dotnet-sdks", "DOTNET_RESULT", "needs.dotnet-sdks.result"),
        ("phpunit-versions", "PHPUNIT_RESULT", "needs.phpunit-versions.result"),
    )
    for name, variable, source in result_bindings:
        invocation = f'require_when {name} "${{EXACT_REQUIRED:-true}}" "${variable}"'
        if not has_line(required, f"{variable}: ${{{{ {source} }}}}") or not has_line(
            required,
            invocation,
        ):
            raise CiContractError(
                f"The required exact-impact aggregate must bind and check {name}"
            )


def has_on_block(workflow: str) -> str:
    """Return the top-level on: block of a workflow."""
    match = re.search(r"(?ms)^on:\n(.*?)(?=^[A-Za-z]|\Z)", workflow)
    return match.group(1) if match else ""


def has_line(block: str, line: str) -> bool:
    """Return whether a job block contains one exact YAML or shell line."""
    return bool(re.search(rf"(?m)^\s*{re.escape(line)}$", block))


def check_merge_queue(root: Path, ci: str, codeql: str, conformance: str) -> None:
    """Required checks must report on merge_group or the merge queue hangs."""
    review = read(root / ".github/workflows/dependency-review.yml")
    queue = read(root / ".github/workflows/queue.yml")
    for name, text in (
        ("ci.yml", ci),
        ("codeql.yml", codeql),
        ("dependency-review.yml", review),
        ("conformance.yml", conformance),
    ):
        if not has_on_trigger(text, "merge_group"):
            raise CiContractError(f"{name} must trigger on merge_group so the merge queue can report required checks")

    if "github.event_name == 'pull_request' || github.event_name == 'merge_group'" not in codeql:
        raise CiContractError("CodeQL pull-request must analyze merge_group")
    if "github.event_name != 'pull_request'" in codeql:
        raise CiContractError("CodeQL main must not run on merge_group")
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
