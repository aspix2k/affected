"""Validate Affected's executable MCP capability matrix."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.support_matrix import SupportMatrixError, read_file, require_string
MATRIX_PATH = Path("config/mcp-capabilities.json")
TOOLSET_PATH = Path("mcp/src/main/kotlin/com/aspix2k/affected/mcp/AffectedToolset.kt")
PLUGIN_PATH = Path("src/main/resources/META-INF/plugin.xml")
MCP_PLUGIN_PATH = Path("mcp/src/main/resources/affected.mcp.xml")
PROPERTIES_PATH = Path("gradle.properties")
REQUIRED_ACTIONS = {
    "com.aspix2k.affected.Run",
    "com.aspix2k.affected.Detekt",
    "com.aspix2k.affected.Lint",
    "com.aspix2k.affected.Coverage",
    "com.aspix2k.affected.Modules",
    "com.aspix2k.affected.CheckConsumers",
    "com.aspix2k.affected.RunBeforeCommit",
    "com.aspix2k.affected.RunBeforePush",
    "com.aspix2k.affected.AnimateWhileRunning",
}
TOOL = re.compile(r"suspend fun (affected_[A-Za-z0-9_]+)\(")
ACTION = re.compile(r'id="(com\.aspix2k\.affected\.[A-Za-z]+)"')
PROPERTY = re.compile(r"^affected\.mcp\.version=(.+)$", re.MULTILINE)
MAX_OPERATIONS = 32


def load_matrix(root: Path = ROOT) -> dict[str, Any]:
    """Load the bounded schema-one MCP capability matrix."""
    try:
        value = json.loads(read_file(root, MATRIX_PATH))
    except json.JSONDecodeError as error:
        raise SupportMatrixError(f"Invalid {MATRIX_PATH}: {error}") from error
    if not isinstance(value, dict) or value.get("schema") != 1:
        raise SupportMatrixError("MCP capability matrix must be a schema-one object")
    return value


def declared_tools(root: Path) -> set[str]:
    """Return MCP tool function names from the production toolset."""
    return set(TOOL.findall(read_file(root, TOOLSET_PATH)))


def declared_actions(root: Path) -> set[str]:
    """Return toolbar and settings action ids from the plugin descriptor."""
    return set(ACTION.findall(read_file(root, PLUGIN_PATH)))


def validated(root: Path, matrix: dict[str, Any]) -> dict[str, Any]:
    """Fail closed when MCP tools, UI actions or the optional module drift."""
    require_string(matrix.get("serverPlugin"), "serverPlugin")
    require_string(matrix.get("versionPin"), "versionPin")
    require_string(matrix.get("module"), "module")
    require_string(matrix.get("toolset"), "toolset")
    operations = matrix.get("operations")
    if not isinstance(operations, list) or not operations or len(operations) > MAX_OPERATIONS:
        raise SupportMatrixError("MCP operations must be a bounded non-empty list")

    tools = declared_tools(root)
    actions = declared_actions(root)
    mapped_tools: set[str] = set()
    mapped_actions: set[str] = set()
    seen_ids: set[str] = set()

    for index, operation in enumerate(operations):
        if not isinstance(operation, dict):
            raise SupportMatrixError(f"Operation {index} must be an object")
        identifier = require_string(operation.get("id"), f"operations[{index}].id")
        if identifier in seen_ids:
            raise SupportMatrixError(f"Duplicate MCP operation: {identifier}")
        seen_ids.add(identifier)
        tool = require_string(operation.get("mcp"), f"operations[{index}].mcp")
        if tool not in tools:
            raise SupportMatrixError(f"Unknown MCP tool: {tool}")
        if tool in mapped_tools:
            raise SupportMatrixError(f"MCP tool mapped twice: {tool}")
        mapped_tools.add(tool)
        kind = require_string(operation.get("kind"), f"operations[{index}].kind")
        if kind not in {"read", "mutating"}:
            raise SupportMatrixError(f"Invalid kind for {identifier}: {kind}")
        ui = operation.get("ui", [])
        mcp_only = operation.get("mcpOnly", False)
        if ui is None:
            ui = []
        if not isinstance(ui, list) or len(ui) > MAX_OPERATIONS:
            raise SupportMatrixError(f"Invalid ui list for {identifier}")
        if mcp_only and ui:
            raise SupportMatrixError(f"{identifier} cannot be MCP-only and map UI actions")
        if not mcp_only and not ui:
            raise SupportMatrixError(f"{identifier} must map a UI action or be marked mcpOnly")
        for action in ui:
            name = require_string(action, f"{identifier}.ui")
            if name not in actions:
                raise SupportMatrixError(f"Unknown UI action: {name}")
            mapped_actions.add(name)

    missing_tools = sorted(tools - mapped_tools)
    if missing_tools:
        raise SupportMatrixError(f"Unmapped MCP tools: {', '.join(missing_tools)}")
    missing_actions = sorted(REQUIRED_ACTIONS - mapped_actions)
    if missing_actions:
        raise SupportMatrixError(f"Unmapped UI actions: {', '.join(missing_actions)}")

    plugin = read_file(root, PLUGIN_PATH)
    if f'<module name="{matrix["module"]}" loading="optional"/>' not in plugin:
        raise SupportMatrixError("MCP module must stay optional in plugin.xml")
    mcp_plugin = read_file(root, MCP_PLUGIN_PATH)
    if matrix["serverPlugin"] not in mcp_plugin:
        raise SupportMatrixError("MCP module must depend on the JetBrains MCP Server plugin")
    if matrix["toolset"] not in mcp_plugin:
        raise SupportMatrixError("MCP module must register the documented toolset")
    properties = read_file(root, PROPERTIES_PATH)
    if not PROPERTY.search(properties):
        raise SupportMatrixError("affected.mcp.version must stay pinned in gradle.properties")
    return matrix


def render_section(matrix: dict[str, Any]) -> str:
    """Render the support-page MCP section from the capability matrix."""
    lines = [
        "## JetBrains MCP Server",
        "",
        "The optional JetBrains MCP Server plugin exposes the same analysis snapshot and",
        "exclusive run lease as the toolbar. Read tools never write project state.",
        "",
        "| Operation | MCP tool | Kind | UI counterpart |",
        "|---|---|---|---|",
    ]
    for operation in matrix["operations"]:
        ui = ", ".join(f"`{action}`" for action in operation.get("ui", [])) or "MCP-only"
        extra = operation.get("mcpOnlyFields")
        if extra:
            ui = f"{ui}; MCP-only fields: {', '.join(f'`{field}`' for field in extra)}"
        lines.append(
            f"| {operation['id']} | `{operation['mcp']}` | {operation['kind']} | {ui} |"
        )
    lines.append("")
    return "\n".join(lines)


def check(root: Path = ROOT) -> None:
    """Fail when the MCP matrix, toolset or optional module have drifted."""
    validated(root, load_matrix(root))


def parse_args(arguments: list[str] | None = None) -> argparse.Namespace:
    """Parse the single explicit check operation."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", required=True)
    return parser.parse_args(arguments)


def main(arguments: list[str] | None = None) -> int:
    """Run the selected operation with concise human-readable errors."""
    try:
        parse_args(arguments)
        check()
        print("MCP capability matrix is complete and current.")
        return 0
    except SupportMatrixError as error:
        print(f"MCP capability error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
