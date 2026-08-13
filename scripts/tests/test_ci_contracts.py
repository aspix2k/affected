"""Regression tests for the fail-closed pull-request CI shape."""

from __future__ import annotations

import subprocess
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from scripts import ci_contracts


class CiContractsTest(unittest.TestCase):
    """Reject a split Gradle graph, a fake CLI matrix and a missing aggregator."""

    def test_current_repository_must_satisfy_the_contract(self) -> None:
        """Validate the production workflows."""
        ci_contracts.check(Path(__file__).resolve().parents[2])

    def test_codeql_kotlin_compatibility_build_is_isolated_and_bounded(self) -> None:
        """Keep the supported analysis compiler out of product builds and unsafe build caches."""
        mutations = {
            "missing compiler pin": ('  CODEQL_KOTLIN_VERSION: "2.4.10"\n', ""),
            "product compiler": ('  CODEQL_KOTLIN_VERSION: "2.4.10"', '  CODEQL_KOTLIN_VERSION: "2.4.20-Beta2"'),
            "missing override": (' -Paffected.kotlin.version="$CODEQL_KOTLIN_VERSION"', ""),
            "build cache": (" --no-build-cache", ""),
        }
        for name, (old, new) in mutations.items():
            with self.subTest(name=name), TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_workflows(root)
                path = root / ".github/workflows/codeql.yml"
                text = path.read_text(encoding="utf-8")
                self.assertIn(old, text)
                path.write_text(text.replace(old, new, 1), encoding="utf-8")
                with self.assertRaisesRegex(ci_contracts.CiContractError, "CodeQL Kotlin"):
                    ci_contracts.check(root)

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

    def test_cache_redirector_is_rejected_from_dependency_acquisition(self) -> None:
        """Keep every tracked build and release dependency on an official direct endpoint."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            nested = root / "deep" / "nested" / "build.gradle.kts"
            nested.parent.mkdir(parents=True)
            forbidden = "cache-" + "redirector.jetbrains.com"
            nested.write_text(f'repositories {{ maven("https://{forbidden}/repo1.maven.org/maven2") }}\n')
            subprocess.run(
                ["git", "add", "deep/nested/build.gradle.kts"],
                cwd=root,
                check=True,
                timeout=10,
            )

            with self.assertRaisesRegex(ci_contracts.CiContractError, "cache redirector"):
                ci_contracts.check(root)

    def test_git_dependency_scan_timeout_fails_closed(self) -> None:
        """Never replace a failed tracked-file inventory with an incomplete fallback scan."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)

            with (
                patch.object(
                    ci_contracts.subprocess,
                    "check_output",
                    side_effect=subprocess.TimeoutExpired("git ls-files", 10),
                ),
                self.assertRaisesRegex(ci_contracts.CiContractError, "tracked dependency acquisition files"),
            ):
                ci_contracts.check(root)

    def test_intellij_cache_redirector_must_be_disabled(self) -> None:
        """Prevent default IntelliJ repositories from silently restoring the redirector."""
        mutations = {
            "missing": "",
            "append true": (
                "org.jetbrains.intellij.platform.useCacheRedirector=false\n"
                "org.jetbrains.intellij.platform.useCacheRedirector=true\n"
            ),
            "prepend true": (
                "org.jetbrains.intellij.platform.useCacheRedirector=true\n"
                "org.jetbrains.intellij.platform.useCacheRedirector=false\n"
            ),
            "duplicate false": (
                "org.jetbrains.intellij.platform.useCacheRedirector=false\n"
                "org.jetbrains.intellij.platform.useCacheRedirector=false\n"
            ),
            "comment and whitespace": (
                "# org.jetbrains.intellij.platform.useCacheRedirector=false\n"
                "org.jetbrains.intellij.platform.useCacheRedirector = false\n"
            ),
            "colon separator": (
                "org.jetbrains.intellij.platform.useCacheRedirector=false\n"
                "org.jetbrains.intellij.platform.useCacheRedirector:true\n"
            ),
            "whitespace separator": (
                "org.jetbrains.intellij.platform.useCacheRedirector=false\n"
                "org.jetbrains.intellij.platform.useCacheRedirector true\n"
            ),
            "leading whitespace": " org.jetbrains.intellij.platform.useCacheRedirector=false\n",
            "escaped key": (
                "org.jetbrains.intellij.platform.useCacheRedirector=false\n"
                "org.jetbrains.intellij.platform.useCache\\Redirector=true\n"
            ),
            "unicode escaped prefix": (
                "org.jetbrains.intellij.platform.useCacheRedirector=false\n"
                "org.jetbrains.intellij.platform.\\u0075seCacheRedirector=true\n"
            ),
            "unicode escaped suffix": (
                "org.jetbrains.intellij.platform.useCacheRedirector=false\n"
                "org.jetbrains.intellij.platform.useCacheRedirecto\\u0072=true\n"
            ),
        }
        for name, setting in mutations.items():
            with self.subTest(mutation=name), TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_workflows(root)
                properties = root / "gradle.properties"
                properties.write_text(
                    properties.read_text(encoding="utf-8").replace(
                        "org.jetbrains.intellij.platform.useCacheRedirector=false\n",
                        setting,
                    ),
                    encoding="utf-8",
                )

                with self.assertRaisesRegex(ci_contracts.CiContractError, "useCacheRedirector=false"):
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
            "scripts/release_currentness.py",
            "settings.gradle.kts",
            "build.gradle.kts",
            "core/build.gradle.kts",
            "collector/build.gradle.kts",
            "mcp/build.gradle.kts",
            "gradle.properties",
            "gradle/wrapper/gradle-wrapper.properties",
        ):
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text((production / relative).read_text(encoding="utf-8"), encoding="utf-8")
        subprocess.run(["git", "init", "-q"], cwd=root, check=True, timeout=10)
        subprocess.run(["git", "add", "."], cwd=root, check=True, timeout=10)

if __name__ == "__main__":
    unittest.main()
