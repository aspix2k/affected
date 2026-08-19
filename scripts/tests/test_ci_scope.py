"""Regression tests for expensive CI scope classification."""

from __future__ import annotations

import subprocess
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from scripts import ci_scope


class CiScopeTest(unittest.TestCase):
    """Keep docs cheap and unknown paths fail-closed."""

    def test_readme_only_skips_expensive_gates(self) -> None:
        """A landing-page edit is not plugin, CodeQL or dependency evidence."""
        self.assertEqual(ci_scope.scope_for(["README.md"]), ci_scope.empty_scope())

    def test_docs_and_script_tests_stay_cheap(self) -> None:
        """Docs, issue templates and script unit tests stay on the scripts job."""
        self.assertEqual(
            ci_scope.scope_for(
                [
                    "AGENTS.md",
                    "docs/CHANGELOG.md",
                    "docs/superpowers/plans/x.md",
                    ".github/ISSUE_TEMPLATE/bug.md",
                    "scripts/ci_scope.py",
                    "scripts/changelog_fragments.py",
                    "scripts/local_gate.py",
                    "scripts/tests/test_ci_scope.py",
                    ".githooks/pre-commit",
                    ".github/dependabot.yml",
                    ".github/workflows/dependency-graph.yml",
                    ".gitignore",
                    ".gitattributes",
                ]
            ),
            ci_scope.empty_scope(),
        )

    def test_gitignore_does_not_open_dependency_review(self) -> None:
        """Ignore files do not change resolved dependencies or require a PR snapshot."""
        self.assertEqual(ci_scope.scope_for([".gitignore"]), ci_scope.empty_scope())
        self.assertEqual(
            ci_scope.scope_for([".gitignore", "core/src/main/kotlin/Foo.kt"]),
            {"plugin": True, "health": False, "codeql": True, "dependencies": False, "exact": True},
        )

    def test_kotlin_source_runs_plugin_and_codeql(self) -> None:
        """Product JVM source still pays for verification and CodeQL."""
        self.assertEqual(
            ci_scope.scope_for(["core/src/main/kotlin/Foo.kt"]),
            {"plugin": True, "health": False, "codeql": True, "dependencies": False, "exact": True},
        )

    def test_root_ui_source_does_not_start_native_adapter_fixtures(self) -> None:
        """Keep the exact matrix at its previous product boundary."""
        self.assertEqual(
            ci_scope.scope_for(["src/main/kotlin/Action.kt"]),
            {"plugin": True, "health": False, "codeql": True, "dependencies": False, "exact": False},
        )

    def test_gradle_lock_runs_every_expensive_gate(self) -> None:
        """Resolved dependencies can change health, CodeQL and review."""
        self.assertEqual(ci_scope.scope_for(["gradle.properties"]), ci_scope.full_scope())

    def test_ci_workflow_keeps_the_fast_gate(self) -> None:
        """Editing the aggregator must still run plugin and health."""
        self.assertEqual(
            ci_scope.scope_for([".github/workflows/ci.yml"]),
            {"plugin": True, "health": True, "codeql": False, "dependencies": False, "exact": False},
        )

    def test_conformance_workflow_runs_its_required_gate(self) -> None:
        """Changing the gate itself must execute every exact-impact lane."""
        self.assertEqual(
            ci_scope.scope_for([".github/workflows/conformance.yml"]),
            {"plugin": False, "health": False, "codeql": False, "dependencies": False, "exact": True},
        )

    def test_codeql_kotlin_compatibility_runs_only_codeql(self) -> None:
        """The analysis compiler shim and probe must not affect product builds."""
        self.assertEqual(
            ci_scope.scope_for(
                [
                    "scripts/codeql-kotlin-compat.init.gradle",
                    "scripts/codeql_kotlin_compat_probe.py",
                ]
            ),
            {
                "plugin": False,
                "health": False,
                "codeql": True,
                "dependencies": False,
                "exact": False,
            },
        )

    def test_plugin_descriptor_keeps_plugin_and_exact_verification(self) -> None:
        """Adding native evidence must not erase the existing plugin gate."""
        self.assertEqual(
            ci_scope.scope_for(["src/main/resources/META-INF/plugin.xml"]),
            {"plugin": True, "health": False, "codeql": False, "dependencies": False, "exact": True},
        )

    def test_product_verifier_matrix_sources_run_the_plugin_gate(self) -> None:
        """Changing product cells or their builder must execute the governed matrix."""
        for path in (
            "config/support-matrix.json",
            "scripts/support_matrix.py",
            "scripts/plugin_verifier_reports.py",
        ):
            with self.subTest(path=path):
                self.assertEqual(
                    ci_scope.scope_for([path]),
                    {
                        "plugin": True,
                        "health": False,
                        "codeql": False,
                        "dependencies": False,
                        "exact": True,
                    },
                )

    def test_unknown_path_is_fail_closed(self) -> None:
        """An unclassified file must not skip a required gate."""
        self.assertEqual(ci_scope.scope_for(["mystery.bin"]), ci_scope.full_scope())

    def test_empty_diff_is_fail_closed(self) -> None:
        """No paths means detection failed; run everything."""
        self.assertEqual(ci_scope.scope_for([]), ci_scope.full_scope())

    def test_mixed_docs_and_source_keeps_source_gates(self) -> None:
        """A docs file cannot turn off a product change in the same diff."""
        self.assertEqual(
            ci_scope.scope_for(["README.md", "core/src/main/kotlin/Bar.kt"]),
            {"plugin": True, "health": False, "codeql": True, "dependencies": False, "exact": True},
        )

    def test_merge_group_uses_group_shas(self) -> None:
        """Queue batches must classify the merge_group range, not a PR file list."""
        base = "a" * 40
        head = "b" * 40
        self.assertEqual(
            ci_scope.event_range(
                "merge_group",
                {"merge_group": {"base_sha": base, "head_sha": head}},
            ),
            (base, head),
        )

    def test_zero_push_base_is_unusable(self) -> None:
        """A new-branch push has no parent and must fail closed."""
        self.assertIsNone(
            ci_scope.event_range("push", {"before": "0" * 40, "after": "c" * 40})
        )

    def test_github_output_writes_lowercase_booleans(self) -> None:
        """Actions conditions compare against the string true."""
        with TemporaryDirectory() as directory:
            path = Path(directory) / "output"
            ci_scope.write_github_output(path, ci_scope.empty_scope())
            self.assertEqual(
                path.read_text(encoding="utf-8"),
                "plugin=false\nhealth=false\ncodeql=false\ndependencies=false\nexact=false\n",
            )

    def test_git_diff_classifies_a_readme_commit(self) -> None:
        """The classifier reads the same names git would send to CI."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.run_git(root, ["init", "-b", "main"])
            self.run_git(root, ["config", "user.email", "ci@example.com"])
            self.run_git(root, ["config", "user.name", "CI"])
            (root / "README.md").write_text("one\n", encoding="utf-8")
            self.run_git(root, ["add", "README.md"])
            self.run_git(root, ["commit", "-m", "base"])
            base = self.run_git(root, ["rev-parse", "HEAD"])
            (root / "README.md").write_text("two\n", encoding="utf-8")
            self.run_git(root, ["commit", "-am", "docs"])
            head = self.run_git(root, ["rev-parse", "HEAD"])
            self.assertEqual(ci_scope.git_changed_files(root, base, head), ["README.md"])
            self.assertEqual(ci_scope.scope_for(["README.md"]), ci_scope.empty_scope())

    def test_git_diff_keeps_both_sides_of_a_product_rename(self) -> None:
        """Moving product source into docs must still run its deletion gates."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.run_git(root, ["init", "-b", "main"])
            self.run_git(root, ["config", "user.email", "ci@example.com"])
            self.run_git(root, ["config", "user.name", "CI"])
            source = root / "core/src/main/kotlin/Foo.kt"
            source.parent.mkdir(parents=True)
            (root / "docs").mkdir()
            source.write_text("class Foo\n", encoding="utf-8")
            self.run_git(root, ["add", "."])
            self.run_git(root, ["commit", "-m", "base"])
            base = self.run_git(root, ["rev-parse", "HEAD"])
            self.run_git(root, ["mv", "core/src/main/kotlin/Foo.kt", "docs/Foo.kt"])
            self.run_git(root, ["commit", "-m", "move source"])
            head = self.run_git(root, ["rev-parse", "HEAD"])

            paths = ci_scope.git_changed_files(root, base, head)

            self.assertEqual(paths, ["core/src/main/kotlin/Foo.kt", "docs/Foo.kt"])
            self.assertEqual(
                ci_scope.scope_for(paths),
                {
                    "plugin": True,
                    "health": False,
                    "codeql": True,
                    "dependencies": False,
                    "exact": True,
                },
            )

    def run_git(self, root: Path, arguments: list[str]) -> str:
        """Run one git command in a temporary repository."""
        result = subprocess.run(
            ["git", "-C", str(root), *arguments],
            check=True,
            capture_output=True,
            text=True,
        )
        return result.stdout.strip()


if __name__ == "__main__":
    unittest.main()
