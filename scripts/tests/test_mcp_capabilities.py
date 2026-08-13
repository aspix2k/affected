"""Tests for the executable MCP capability matrix."""

from __future__ import annotations

import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from scripts import mcp_capabilities
from scripts.support_matrix import SupportMatrixError


class McpCapabilitiesTest(unittest.TestCase):
    """Keep every MCP tool and toolbar action mapped and optional."""

    def test_unmapped_tool_is_rejected(self) -> None:
        """Fail when the toolset grows without a capability entry."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            source = (root / "mcp/src/main/kotlin/com/aspix2k/affected/mcp/AffectedToolset.kt")
            source.write_text(
                source.read_text(encoding="utf-8") + "\n    suspend fun affected_secret(): String = \"\"\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(SupportMatrixError, "affected_secret"):
                mcp_capabilities.check(root)

    def test_optional_module_is_required(self) -> None:
        """Reject a required MCP module that would break IDEs without the server plugin."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            plugin = root / "src/main/resources/META-INF/plugin.xml"
            plugin.write_text(
                plugin.read_text(encoding="utf-8").replace(
                    'loading="optional"', 'loading="required"'
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(SupportMatrixError, "optional"):
                mcp_capabilities.check(root)

    def test_repository_matrix_is_complete(self) -> None:
        """Validate the production MCP matrix against the toolset and plugin descriptors."""
        mcp_capabilities.check(Path(__file__).resolve().parents[2])

    def write_repository(self, root: Path) -> None:
        """Create a minimal repository accepted by the MCP capability validator."""
        production = Path(__file__).resolve().parents[2]
        matrix = json.loads((production / "config/mcp-capabilities.json").read_text())
        (root / "config").mkdir(parents=True)
        (root / "config/mcp-capabilities.json").write_text(json.dumps(matrix), encoding="utf-8")
        for relative in (
            "mcp/src/main/kotlin/com/aspix2k/affected/mcp/AffectedToolset.kt",
            "mcp/src/main/resources/affected.mcp.xml",
            "src/main/resources/META-INF/plugin.xml",
            "gradle.properties",
        ):
            source = production / relative
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(source.read_text(encoding="utf-8"), encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
