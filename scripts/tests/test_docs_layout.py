"""Keep the GitHub landing page to README and LICENSE."""

from __future__ import annotations

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
