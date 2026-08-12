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
                    '- "SUPPORT.md"\n',
                    '- "SUPPORT.md"\n      - "README.md"\n',
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
            ".github/workflows/queue.yml",
            "scripts/ci_scope.py",
            "scripts/run_gradle.sh",
            "gradle/wrapper/gradle-wrapper.properties",
        ):
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text((production / relative).read_text(encoding="utf-8"), encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
