"""Keep the GitHub landing page to README and LICENSE."""

from __future__ import annotations

import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOC_FILES = (
    "CHANGELOG.md",
    "CODE_OF_CONDUCT.md",
    "CONTRIBUTING.md",
    "PRIVACY.md",
    "SECURITY.md",
    "SUPPORT.md",
)

MARKDOWN_LINK = re.compile(r"\[[^\]]*\]\(([^)]+)\)")


class DocsLayoutTest(unittest.TestCase):
    """Community files live under docs/; the root stays a landing page."""

    def test_root_markdown_is_only_readme(self) -> None:
        """GitHub's first page must not list a sheet of policy files."""
        tracked = subprocess.check_output(["git", "ls-files", "*.md"], cwd=ROOT, text=True)
        names = sorted(Path(line).name for line in tracked.splitlines() if "/" not in line)
        self.assertEqual(names, ["README.md"])

    def test_community_files_live_under_docs(self) -> None:
        """GitHub still discovers CONTRIBUTING, SECURITY and conduct in docs/."""
        for name in DOC_FILES:
            path = ROOT / "docs" / name
            self.assertTrue(path.is_file(), f"missing {path.relative_to(ROOT)}")

    def test_changelog_plugin_reads_docs_changelog(self) -> None:
        """patchPluginXml must not look for a root CHANGELOG.md."""
        build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('path = "docs/CHANGELOG.md"', build)

    def test_tracked_markdown_relative_links_resolve(self) -> None:
        """Fail when a public markdown page points at a missing repository path."""
        tracked = subprocess.check_output(["git", "ls-files", "*.md"], cwd=ROOT, text=True)
        missing: list[str] = []
        for relative in tracked.splitlines():
            source = ROOT / relative
            text = source.read_text(encoding="utf-8")
            for href in MARKDOWN_LINK.findall(text):
                target = href.split()[0].strip("<>")
                if target.startswith(("http://", "https://", "mailto:", "#")):
                    continue
                path = target.split("#", 1)[0]
                if not path:
                    continue
                resolved = (source.parent / path).resolve()
                try:
                    resolved.relative_to(ROOT.resolve())
                except ValueError:
                    missing.append(f"{relative} -> {target}")
                    continue
                if not resolved.exists():
                    missing.append(f"{relative} -> {target}")
        self.assertEqual(missing, [])
