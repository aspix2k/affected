"""Regression tests for product changelog fragments."""

from __future__ import annotations

import subprocess
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from scripts import changelog_fragments


SAMPLE_CHANGELOG = """# Changelog

## [Unreleased]

### Fixed

- Keep an existing product fix.

### Added

- Keep an existing product addition.

## [2.0.1]

### Added

- Shipped product.
"""


class ChangelogFragmentsTest(unittest.TestCase):
    """PRs never edit docs/CHANGELOG.md; Marketplace notes stay product-only."""

    def test_infra_pull_request_needs_no_fragment(self) -> None:
        """Coverage and CI work must not invent Marketplace news."""
        changelog_fragments.check_paths(["scripts/ci_scope.py"], root=self.repo())

    def test_product_fragment_is_accepted(self) -> None:
        """A uniquely named fragment is how two product PRs stay mergeable."""
        root = self.repo()
        self.write_fragment(root, "android-instrumentation.fixed.md", "- Select connected tests.\n")
        changelog_fragments.check_paths(
            ["core/src/main/kotlin/Foo.kt", "docs/changelog.d/android-instrumentation.fixed.md"],
            root=root,
        )

    def test_assembled_changelog_edit_is_rejected(self) -> None:
        """Two PRs editing Unreleased is what dirties the merge train."""
        with self.assertRaisesRegex(changelog_fragments.ChangelogError, "docs/CHANGELOG.md"):
            changelog_fragments.check_paths(["docs/CHANGELOG.md"], root=self.repo())

    def test_release_cut_may_edit_assembled_changelog(self) -> None:
        """The release PR assembles Marketplace notes when it also sets version."""
        root = self.repo()
        (root / "build.gradle.kts").write_text('version = "3.0.0"\n', encoding="utf-8")
        (root / "docs/CHANGELOG.md").write_text(
            SAMPLE_CHANGELOG.replace("## [2.0.1]", "## [3.0.0] - 2026-08-14\n\n### Added\n\n- Ship adapters.\n\n## [2.0.1]"),
            encoding="utf-8",
        )
        changelog_fragments.check_paths(["build.gradle.kts", "docs/CHANGELOG.md"], root=root)

    def test_release_cut_rejects_infrastructure_in_version_section(self) -> None:
        """Marketplace What's New for the cut version stays product-only."""
        root = self.repo()
        (root / "build.gradle.kts").write_text('version = "3.0.0"\n', encoding="utf-8")
        (root / "docs/CHANGELOG.md").write_text(
            "## [Unreleased]\n\n## [3.0.0]\n\n### Changed\n\n- Raise the Kover line floor to 60.\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(changelog_fragments.ChangelogError, "Kover"):
            changelog_fragments.check_paths(["build.gradle.kts", "docs/CHANGELOG.md"], root=root)

    def test_release_cut_may_consume_fragments(self) -> None:
        """patchChangelog deletes the fragments it folded into the version section."""
        root = self.repo()
        (root / "build.gradle.kts").write_text('version = "3.0.0"\n', encoding="utf-8")
        (root / "docs/CHANGELOG.md").write_text(
            SAMPLE_CHANGELOG.replace("## [2.0.1]", "## [3.0.0] - 2026-08-14\n\n### Added\n\n- Ship adapters.\n\n## [2.0.1]"),
            encoding="utf-8",
        )
        changelog_fragments.check_paths(
            [
                "build.gradle.kts",
                "docs/CHANGELOG.md",
                "docs/changelog.d/nested-ninja-root.added.md",
            ],
            root=root,
        )

    def test_deleted_fragment_without_a_release_is_rejected(self) -> None:
        """A product PR cannot drop a fragment that was never assembled."""
        with self.assertRaisesRegex(changelog_fragments.ChangelogError, "deleted"):
            changelog_fragments.check_paths(
                ["docs/changelog.d/nested-ninja-root.added.md"],
                root=self.repo(),
            )

    def test_empty_fragment_is_rejected(self) -> None:
        """An empty file cannot become Marketplace What's New."""
        root = self.repo()
        self.write_fragment(root, "empty.added.md", "\n")
        with self.assertRaisesRegex(changelog_fragments.ChangelogError, "empty"):
            changelog_fragments.check_paths(["docs/changelog.d/empty.added.md"], root=root)

    def test_unknown_fragment_name_is_rejected(self) -> None:
        """Keep a Changelog types are the only Marketplace sections."""
        root = self.repo()
        self.write_fragment(root, "notes.md", "- A product change.\n")
        with self.assertRaisesRegex(changelog_fragments.ChangelogError, "notes.md"):
            changelog_fragments.check_paths(["docs/changelog.d/notes.md"], root=root)

    def test_heading_fragment_is_rejected(self) -> None:
        """A fragment is one bullet, not a second changelog document."""
        root = self.repo()
        self.write_fragment(root, "heading.added.md", "### Added\n\n- A product change.\n")
        with self.assertRaisesRegex(changelog_fragments.ChangelogError, "bullet"):
            changelog_fragments.check_paths(["docs/changelog.d/heading.added.md"], root=root)

    def test_infrastructure_wording_is_rejected(self) -> None:
        """Marketplace What's New is the widget, never CI plumbing."""
        root = self.repo()
        self.write_fragment(root, "kover.changed.md", "- Raise the Kover line floor to 60.\n")
        with self.assertRaisesRegex(changelog_fragments.ChangelogError, "Kover"):
            changelog_fragments.check_paths(["docs/changelog.d/kover.changed.md"], root=root)

    def test_rebase_note_is_rejected(self) -> None:
        """Rebase and coverage-floor notes are not product news."""
        root = self.repo()
        self.write_fragment(root, "train.fixed.md", "- Rebase the merge train after a DIRTY changelog.\n")
        with self.assertRaisesRegex(changelog_fragments.ChangelogError, "Rebase"):
            changelog_fragments.check_paths(["docs/changelog.d/train.fixed.md"], root=root)

    def test_render_prepends_an_existing_section(self) -> None:
        """Newest product bullets sit above already assembled Unreleased notes."""
        root = self.repo()
        self.write_fragment(root, "android-instrumentation.fixed.md", "- Select connected tests.\n")
        self.assertTrue(changelog_fragments.render(root))
        text = (root / "docs/CHANGELOG.md").read_text(encoding="utf-8")
        self.assertIn("### Fixed\n\n- Select connected tests.\n\n- Keep an existing product fix.", text)
        self.assertFalse((root / "docs/changelog.d/android-instrumentation.fixed.md").exists())
        self.assertTrue((root / "docs/changelog.d/.gitkeep").is_file())

    def test_render_creates_a_missing_section(self) -> None:
        """A new Keep a Changelog type still lands under Unreleased."""
        root = self.repo()
        self.write_fragment(root, "old-adapter.removed.md", "- Drop the unused adapter.\n")
        changelog_fragments.render(root)
        text = (root / "docs/CHANGELOG.md").read_text(encoding="utf-8")
        self.assertIn("### Removed\n\n- Drop the unused adapter.\n", text)
        self.assertIn("## [2.0.1]", text)

    def test_render_is_a_noop_without_fragments(self) -> None:
        """An infrastructure land must not rewrite CHANGELOG.md."""
        root = self.repo()
        before = (root / "docs/CHANGELOG.md").read_text(encoding="utf-8")
        self.assertFalse(changelog_fragments.render(root))
        self.assertEqual(before, (root / "docs/CHANGELOG.md").read_text(encoding="utf-8"))

    def test_render_fails_without_unreleased(self) -> None:
        """Assembly has nowhere to put product news if Unreleased is gone."""
        root = self.repo()
        (root / "docs/CHANGELOG.md").write_text("## [2.0.1]\n\n- Shipped.\n", encoding="utf-8")
        self.write_fragment(root, "android-instrumentation.fixed.md", "- Select connected tests.\n")
        with self.assertRaisesRegex(changelog_fragments.ChangelogError, "Unreleased"):
            changelog_fragments.render(root)

    def test_check_reads_the_git_range(self) -> None:
        """CI classifies the same names git would send for the pull request."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.init_git(root)
            (root / "docs/changelog.d").mkdir(parents=True)
            (root / "docs/changelog.d/.gitkeep").write_text("", encoding="utf-8")
            (root / "docs/CHANGELOG.md").write_text(SAMPLE_CHANGELOG, encoding="utf-8")
            (root / "README.md").write_text("base\n", encoding="utf-8")
            self.run_git(root, ["add", "docs", "README.md"])
            self.run_git(root, ["commit", "-m", "base"])
            self.write_fragment(root, "android-instrumentation.fixed.md", "- Select connected tests.\n")
            self.run_git(root, ["add", "docs/changelog.d/android-instrumentation.fixed.md"])
            self.run_git(root, ["commit", "-m", "product"])
            changelog_fragments.check(root, base="HEAD~1")

    def repo(self) -> Path:
        """Build a temporary repository that already has Unreleased notes."""
        directory = TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        (root / "docs/changelog.d").mkdir(parents=True)
        (root / "docs/changelog.d/.gitkeep").write_text("", encoding="utf-8")
        (root / "docs/CHANGELOG.md").write_text(SAMPLE_CHANGELOG, encoding="utf-8")
        return root

    def write_fragment(self, root: Path, name: str, body: str) -> None:
        """Write one candidate fragment file."""
        (root / "docs/changelog.d" / name).write_text(body, encoding="utf-8")

    def init_git(self, root: Path) -> None:
        """Create a commit-ready git repository."""
        self.run_git(root, ["init", "-b", "main"])
        self.run_git(root, ["config", "user.email", "ci@example.com"])
        self.run_git(root, ["config", "user.name", "CI"])

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
