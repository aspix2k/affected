"""Regression tests for the fail-closed pull-request CI shape."""

from __future__ import annotations

import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from scripts import ci_contracts


class CiContractsTest(unittest.TestCase):
    """Reject a split Gradle graph, a fake CLI matrix and a missing aggregator."""

    def test_current_repository_must_satisfy_the_contract(self) -> None:
        """Validate the production workflows."""
        ci_contracts.check(Path(__file__).resolve().parents[2])

    def test_three_gradle_invocations_are_rejected(self) -> None:
        """Keep plugin analysis, tests and verification on one Gradle graph."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            ci = root / ".github/workflows/ci.yml"
            ci.write_text(
                ci.read_text(encoding="utf-8").replace(
                    "scripts/run_gradle.sh --no-daemon --max-workers=2",
                    "scripts/run_gradle.sh :detekt\n          ./gradlew --no-daemon --max-workers=2",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "exactly once"):
                ci_contracts.check(root)

    def test_wrapper_must_not_use_a_single_ten_second_fetch(self) -> None:
        """A 10s timeout with retries=0 is how CI died on services.gradle.org."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            wrapper = root / "gradle/wrapper/gradle-wrapper.properties"
            wrapper.write_text(
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.0-bin.zip\n"
                "distributionSha256Sum=abc\n"
                "networkTimeout=10000\n"
                "retries=0\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "networkTimeout"):
                ci_contracts.check(root)

    def test_readme_must_not_start_conformance(self) -> None:
        """Documentation-only README edits are not exact-impact evidence."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/conformance.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    '- "docs/SUPPORT.md"\n',
                    '- "docs/SUPPORT.md"\n      - "README.md"\n',
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "README"):
                ci_contracts.check(root)

    def test_required_checks_must_run_on_merge_group(self) -> None:
        """A merge queue without merge_group waits forever for verify."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/ci.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace("  merge_group:\n", "", 1),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "merge_group"):
                ci_contracts.check(root)

    def test_plugin_must_stay_scoped(self) -> None:
        """A required plugin job without a scope condition re-downloads IDEs for docs."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/ci.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "    if: needs.scope.outputs.plugin == 'true'\n",
                    "",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "plugin must run only"):
                ci_contracts.check(root)

    def test_queue_must_not_merge_immediately(self) -> None:
        """Agents enqueue. GitHub merges after required checks."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/queue.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(" --auto", "", 1),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "--auto"):
                ci_contracts.check(root)

    def test_dependabot_version_update_prs_are_rejected(self) -> None:
        """Keep dependency discovery read-only instead of opening bot pull requests."""
        for filename in ("dependabot.yml", "dependabot.yaml"):
            with self.subTest(filename=filename), TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_workflows(root)
                dependabot = root / ".github" / filename
                dependabot.write_text(
                    "version: 2\nupdates:\n  - package-ecosystem: gradle\n    directory: /\n",
                    encoding="utf-8",
                )
                with self.assertRaisesRegex(ci_contracts.CiContractError, "Dependabot version-update pull requests"):
                    ci_contracts.check(root)

    def test_review_must_not_call_the_compare_api(self) -> None:
        """Submit never publishes PR-head snapshots, so compare cannot be complete."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/dependency-review.yml"
            path.write_text(
                path.read_text(encoding="utf-8")
                + "\n      - run: curl $GITHUB_API_URL/repos/x/y/dependency-graph/compare/a...b\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "compare API"):
                ci_contracts.check(root)

    def test_generate_must_require_a_complete_snapshot(self) -> None:
        """A PR compare cannot prove the graph; generate must keep the artifact check."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/dependency-graph.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "Require a complete dependency snapshot",
                    "Validate dependency snapshot",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "complete snapshot"):
                ci_contracts.check(root)

    def test_submit_must_not_run_for_pull_request_graphs(self) -> None:
        """A PR whose generate job is skipped still concludes success and emails on submit failure."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/dependency-graph-submit.yml"
            text = path.read_text(encoding="utf-8")
            path.write_text(
                text.replace(
                    "github.event.workflow_run.event == 'push'",
                    "github.event.workflow_run.event == 'pull_request'",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "pull-request graphs"):
                ci_contracts.check(root)

    def test_submit_must_accept_main_workflow_dispatch(self) -> None:
        """GITHUB_TOKEN merges do not fire push generate; dispatch is the backfill."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/dependency-graph-submit.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace("push|workflow_dispatch", "push", 1),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "workflow_dispatch"):
                ci_contracts.check(root)

    def test_kover_must_include_core_and_mcp(self) -> None:
        """MCP and core tests must count toward the coverage floor."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / "build.gradle.kts"
            path.write_text(
                path.read_text(encoding="utf-8").replace('kover(project(":mcp"))\n', "", 1),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "Kover must verify"):
                ci_contracts.check(root)

    def test_kover_line_floor_cannot_drop_below_sixty(self) -> None:
        """A 19% floor no longer matches the measured :core+:mcp line coverage."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / "build.gradle.kts"
            path.write_text(
                path.read_text(encoding="utf-8").replace("minBound(60)", "minBound(19)", 1),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "at least 60"):
                ci_contracts.check(root)

    def test_release_currentness_must_prefer_cache_redirector_metadata(self) -> None:
        """A Central-only metadata lookup is how jackson-bom 429 failed Scripts."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / "scripts/release_currentness.py"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "https://cache-redirector.jetbrains.com/repo1.maven.org/maven2",
                    "https://example.test/maven2",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "cache-redirector first"):
                ci_contracts.check(root)

    def test_mcp_module_must_enforce_the_patched_jackson_bom(self) -> None:
        """The MCP Server plugin pulls Jackson 2.19 unless the BOM is enforced."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / "mcp/build.gradle.kts"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    'add("intellijPlatformDependencies", enforcedPlatform("com.fasterxml.jackson:jackson-bom:2.22.1"))\n',
                    "",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "Jackson BOM"):
                ci_contracts.check(root)

    def copy_workflows(self, root: Path) -> None:
        """Copy the production workflow set into a temporary repository."""
        production = Path(__file__).resolve().parents[2]
        for relative in (
            ".github/workflows/ci.yml",
            ".github/workflows/conformance.yml",
            ".github/workflows/codeql.yml",
            ".github/workflows/mutation.yml",
            ".github/workflows/dependency-review.yml",
            ".github/workflows/dependency-graph.yml",
            ".github/workflows/dependency-graph-submit.yml",
            ".github/workflows/queue.yml",
            "scripts/ci_scope.py",
            "scripts/release_currentness.py",
            "scripts/run_gradle.sh",
            ".githooks/pre-commit",
            ".githooks/pre-push",
            "docs/CONTRIBUTING.md",
            "settings.gradle.kts",
            "mcp/build.gradle.kts",
            "build.gradle.kts",
            "gradle/wrapper/gradle-wrapper.properties",
        ):
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text((production / relative).read_text(encoding="utf-8"), encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
