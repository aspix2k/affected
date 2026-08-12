#!/usr/bin/env python3
"""Validate and render Affected's executable support matrix."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ElementTree
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
MATRIX_PATH = Path("config/support-matrix.json")
SUPPORT_PATH = Path("SUPPORT.md")
README_PATH = Path("README.md")
PLUGIN_PATH = Path("src/main/resources/META-INF/plugin.xml")
SUMMARY_START = "<!-- affected-support-summary:start -->"
SUMMARY_END = "<!-- affected-support-summary:end -->"
MAX_MATRIX_BYTES = 256 * 1024
MAX_ENTRIES = 128
MAX_EVIDENCE = 16
DATE = re.compile(r"20\d{2}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\d|3[01])")
ISSUE = re.compile(r"https://github\.com/aspix2k/affected/issues/([1-9]\d*)")
IDENTIFIER = re.compile(r"[a-z][a-z0-9-]{1,63}")
ADAPTER_IDENTIFIER = re.compile(r"[A-Z][A-Z0-9_]{1,31}")
SELECTION_LABELS = {
    "test": "test",
    "class": "test class",
    "file": "test file",
    "module": "module",
    "package": "package",
    "project": "project",
    "gem": "gem",
    "target": "target",
}


class SupportMatrixError(RuntimeError):
    """Describe a fail-closed support matrix violation."""


def read_file(root: Path, relative: Path, limit: int = MAX_MATRIX_BYTES) -> str:
    """Read a bounded regular UTF-8 repository file without following symlinks."""
    path = secure_path(root, relative)
    if not path.is_file() or path.is_symlink():
        raise SupportMatrixError(f"Missing regular file: {relative}")
    if path.stat().st_size > limit:
        raise SupportMatrixError(f"File is too large: {relative}")
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        raise SupportMatrixError(f"Cannot read {relative}: {error}") from error


def secure_path(root: Path, relative: Path) -> Path:
    """Resolve a relative repository path and reject traversal outside the root."""
    if relative.is_absolute() or ".." in relative.parts:
        raise SupportMatrixError(f"Unsafe repository path: {relative}")
    base = root.resolve(strict=True)
    candidate = root.joinpath(relative)
    try:
        resolved = candidate.resolve(strict=False)
    except OSError as error:
        raise SupportMatrixError(f"Cannot resolve {relative}: {error}") from error
    if resolved != base and base not in resolved.parents:
        raise SupportMatrixError(f"Path escapes repository root: {relative}")
    return candidate


def load_matrix(root: Path = ROOT) -> dict[str, Any]:
    """Load a bounded schema-one support matrix from the repository."""
    try:
        value = json.loads(read_file(root, MATRIX_PATH))
    except json.JSONDecodeError as error:
        raise SupportMatrixError(f"Invalid {MATRIX_PATH}: {error}") from error
    if not isinstance(value, dict) or value.get("schema") != 1:
        raise SupportMatrixError("Support matrix must be a schema-one object")
    return value


def require_string(
    value: object, field: str, pattern: re.Pattern[str] | None = None
) -> str:
    """Return a non-empty bounded string that satisfies an optional pattern."""
    if not isinstance(value, str) or not value.strip() or len(value) > 512:
        raise SupportMatrixError(f"Expected a non-empty string for {field}")
    result = value.strip()
    if pattern is not None and pattern.fullmatch(result) is None:
        raise SupportMatrixError(f"Invalid {field}: {result}")
    return result


def require_strings(value: object, field: str) -> list[str]:
    """Return a non-empty unique bounded string list."""
    if not isinstance(value, list) or not value or len(value) > MAX_EVIDENCE:
        raise SupportMatrixError(f"Expected a non-empty list for {field}")
    result = [require_string(item, field) for item in value]
    if len(result) != len(set(result)):
        raise SupportMatrixError(f"Duplicate values in {field}")
    return result


def require_evidence(
    root: Path, value: object, field: str, *, regular_files: bool = False
) -> list[str]:
    """Require every declared public fixture or gate to exist inside the repository."""
    paths = require_strings(value, field)
    for text in paths:
        relative = Path(text)
        path = secure_path(root, relative)
        if not path.exists() or path.is_symlink():
            raise SupportMatrixError(f"Missing evidence path for {field}: {text}")
        if regular_files and not path.is_file():
            raise SupportMatrixError(
                f"Evidence gate must be a regular file for {field}: {text}"
            )
    return paths


def registered_adapters(root: Path) -> dict[str, str]:
    """Discover every production BuildSystem extension from plugin descriptors."""
    descriptor_root = secure_path(root, Path("src/main/resources/META-INF"))
    if not descriptor_root.is_dir() or descriptor_root.is_symlink():
        raise SupportMatrixError("Plugin descriptor directory is missing")
    descriptors = sorted(descriptor_root.glob("*.xml"))
    if not descriptors or len(descriptors) > 32:
        raise SupportMatrixError("Unexpected plugin descriptor count")
    discovered: dict[str, str] = {}
    for descriptor in descriptors:
        relative = descriptor.relative_to(root)
        try:
            document = ElementTree.fromstring(read_file(root, relative))
        except ElementTree.ParseError as error:
            raise SupportMatrixError(
                f"Invalid plugin descriptor {relative}: {error}"
            ) from error
        for element in document.iter():
            if element.tag.rsplit("}", 1)[-1] != "buildSystem":
                continue
            implementation = require_string(
                element.get("implementation"), f"{relative} implementation"
            )
            if implementation in discovered:
                raise SupportMatrixError(
                    f"Duplicate BuildSystem registration: {implementation}"
                )
            discovered[implementation] = relative.as_posix()
    if not discovered or len(discovered) > MAX_ENTRIES:
        raise SupportMatrixError("No bounded BuildSystem registrations found")
    return discovered


def validate_products(root: Path, products: object) -> list[dict[str, Any]]:
    """Validate supported and explicitly excluded JetBrains products."""
    if not isinstance(products, list) or not products or len(products) > MAX_ENTRIES:
        raise SupportMatrixError("products must be a non-empty bounded list")
    result: list[dict[str, Any]] = []
    identifiers: set[str] = set()
    names: set[str] = set()
    for raw in products:
        if not isinstance(raw, dict):
            raise SupportMatrixError("Every product must be an object")
        product = dict(raw)
        identifier = require_string(product.get("id"), "product id", IDENTIFIER)
        name = require_string(product.get("name"), f"{identifier} name")
        support = require_string(product.get("support"), f"{identifier} support")
        if identifier in identifiers or name in names:
            raise SupportMatrixError(f"Duplicate product: {identifier}")
        identifiers.add(identifier)
        names.add(name)
        if support in {"excluded", "planned"}:
            reason = require_string(product.get("reason"), f"{identifier} reason")
            reviewed = require_string(
                product.get("reviewed"), f"{identifier} reviewed", DATE
            )
            if len(reason) < 20:
                raise SupportMatrixError(
                    f"{support.title()} product reason is too short: {identifier}"
                )
            product["reviewed"] = reviewed
            if support == "planned":
                product["issue"] = require_string(
                    product.get("issue"), f"{identifier} issue", ISSUE
                )
        elif support in {"verified", "platform"}:
            product["since"] = require_string(
                product.get("since"), f"{identifier} since"
            )
            product["fixtures"] = require_evidence(
                root, product.get("fixtures"), f"{identifier} fixtures"
            )
            product["gates"] = require_evidence(
                root,
                product.get("gates"),
                f"{identifier} gates",
                regular_files=True,
            )
        else:
            raise SupportMatrixError(
                f"Unsupported product level for {identifier}: {support}"
            )
        result.append(product)
    return result


def validate_operating_systems(root: Path, systems: object) -> list[dict[str, Any]]:
    """Validate every claimed operating-system evidence tier."""
    if not isinstance(systems, list) or not systems or len(systems) > 16:
        raise SupportMatrixError("operatingSystems must be a non-empty bounded list")
    result: list[dict[str, Any]] = []
    identifiers: set[str] = set()
    for raw in systems:
        if not isinstance(raw, dict):
            raise SupportMatrixError("Every operating system must be an object")
        system = dict(raw)
        identifier = require_string(system.get("id"), "operating system id", IDENTIFIER)
        if identifier in identifiers:
            raise SupportMatrixError(f"Duplicate operating system: {identifier}")
        identifiers.add(identifier)
        require_string(system.get("name"), f"{identifier} name")
        level = require_string(system.get("support"), f"{identifier} support")
        if level not in {"native", "contract"}:
            raise SupportMatrixError(
                f"Unsupported operating system level for {identifier}: {level}"
            )
        system["fixtures"] = require_evidence(
            root, system.get("fixtures"), f"{identifier} fixtures"
        )
        system["gates"] = require_evidence(
            root,
            system.get("gates"),
            f"{identifier} gates",
            regular_files=True,
        )
        result.append(system)
    return result


def validate_adapters(root: Path, adapters: object) -> list[dict[str, Any]]:
    """Validate adapter claims and require exact registration coverage."""
    if not isinstance(adapters, list) or not adapters or len(adapters) > MAX_ENTRIES:
        raise SupportMatrixError("adapters must be a non-empty bounded list")
    result: list[dict[str, Any]] = []
    identifiers: set[str] = set()
    implementations: set[str] = set()
    for raw in adapters:
        if not isinstance(raw, dict):
            raise SupportMatrixError("Every adapter must be an object")
        adapter = dict(raw)
        identifier = require_string(adapter.get("id"), "adapter id", ADAPTER_IDENTIFIER)
        implementation = require_string(
            adapter.get("implementation"), f"{identifier} implementation"
        )
        if identifier in identifiers or implementation in implementations:
            raise SupportMatrixError(f"Duplicate adapter: {identifier}")
        identifiers.add(identifier)
        implementations.add(implementation)
        if (
            require_string(adapter.get("support"), f"{identifier} support")
            != "supported"
        ):
            raise SupportMatrixError(
                f"Registered adapter must be supported: {identifier}"
            )
        for field in ("ecosystem", "versions"):
            adapter[field] = require_string(adapter.get(field), f"{identifier} {field}")
        for field in ("languages", "runners", "selection"):
            adapter[field] = require_strings(
                adapter.get(field), f"{identifier} {field}"
            )
        unknown_selection = set(adapter["selection"]) - set(SELECTION_LABELS)
        if unknown_selection:
            raise SupportMatrixError(
                f"Unsupported selection units for {identifier}: {sorted(unknown_selection)}"
            )
        proofs = adapter.get("selectionProofs")
        if not isinstance(proofs, dict) or set(proofs) != set(adapter["selection"]):
            raise SupportMatrixError(
                f"{identifier} selection proofs must exactly match its selection units"
            )
        normalized_proofs: dict[str, dict[str, str]] = {}
        for unit in adapter["selection"]:
            proof = proofs[unit]
            if not isinstance(proof, dict) or set(proof) != {"path", "marker"}:
                raise SupportMatrixError(f"Invalid {identifier} {unit} selection proof")
            path = require_evidence(
                root,
                [proof.get("path")],
                f"{identifier} {unit} selection proof",
                regular_files=True,
            )[0]
            marker = require_string(
                proof.get("marker"), f"{identifier} {unit} selection marker"
            )
            occurrences = read_file(root, Path(path)).count(marker)
            if occurrences != 1:
                raise SupportMatrixError(
                    f"{identifier} {unit} selection marker must occur exactly once: {marker}"
                )
            normalized_proofs[unit] = {"path": path, "marker": marker}
        adapter["selectionProofs"] = normalized_proofs
        adapter["fixtures"] = require_evidence(
            root, adapter.get("fixtures"), f"{identifier} fixtures"
        )
        adapter["gates"] = require_evidence(
            root,
            adapter.get("gates"),
            f"{identifier} gates",
            regular_files=True,
        )
        result.append(adapter)
    registered = set(registered_adapters(root))
    if implementations != registered:
        missing = sorted(registered - implementations)
        stale = sorted(implementations - registered)
        raise SupportMatrixError(
            f"Adapter inventory mismatch; missing={missing}, stale={stale}"
        )
    return result


def validated(root: Path, matrix: dict[str, Any]) -> dict[str, Any]:
    """Return a normalized matrix after all fail-closed checks pass."""
    allowed = {"schema", "products", "operatingSystems", "adapters"}
    if set(matrix) != allowed:
        raise SupportMatrixError(
            f"Unexpected support matrix fields: {sorted(set(matrix) - allowed)}"
        )
    return {
        "schema": 1,
        "products": validate_products(root, matrix.get("products")),
        "operatingSystems": validate_operating_systems(
            root, matrix.get("operatingSystems")
        ),
        "adapters": validate_adapters(root, matrix.get("adapters")),
    }


def markdown(text: str) -> str:
    """Escape table separators and line breaks in generated Markdown cells."""
    return text.replace("|", "\\|").replace("\n", " ")


def links(paths: list[str]) -> str:
    """Render concise relative Markdown links for repository evidence paths."""
    return " · ".join(f"[{Path(path).name}]({path})" for path in paths)


def render(matrix: dict[str, Any]) -> str:
    """Render the complete human-facing support page deterministically."""
    products = matrix["products"]
    systems = matrix["operatingSystems"]
    adapters = matrix["adapters"]
    supported_products = [
        product
        for product in products
        if product["support"] in {"verified", "platform"}
    ]
    planned_products = [
        product for product in products if product["support"] == "planned"
    ]
    excluded_products = [
        product for product in products if product["support"] == "excluded"
    ]
    lines = [
        "# Support",
        "",
        "This page is generated from `config/support-matrix.json`. Every supported entry",
        "is tied to public repository evidence and an executable CI gate.",
        "",
        "## JetBrains products",
        "",
        "| Product | Evidence level | Minimum platform | Evidence |",
        "|---|---|---:|---|",
    ]
    product_levels = {"verified": "Product-verified", "platform": "Platform-compatible"}
    for product in supported_products:
        evidence = links(product["fixtures"] + product["gates"])
        lines.append(
            f"| {markdown(product['name'])} | {product_levels[product['support']]} | "
            f"{markdown(product['since'])} | {evidence} |"
        )
    lines += [
        "",
        "Product-verified entries run a product-specific verifier. Platform-compatible",
        "entries share the supported IntelliJ Platform contract but do not yet have a",
        "dedicated product lifecycle fixture.",
    ]
    if planned_products:
        lines += [
            "",
            "## Planned coverage",
            "",
            "| Product | Goal | Tracking issue | Last reviewed |",
            "|---|---|---|---:|",
        ]
        for product in planned_products:
            issue_number = ISSUE.fullmatch(product["issue"]).group(1)
            lines.append(
                f"| {markdown(product['name'])} | {markdown(product['reason'])} | "
                f"[Issue #{issue_number}]({product['issue']}) | {product['reviewed']} |"
            )
    lines += [
        "",
        "## Build systems and test runners",
        "",
        "| Ecosystem | Languages and files | Tests and checks | Selection unit | Tested versions | Evidence |",
        "|---|---|---|---|---|---|",
    ]
    for adapter in adapters:
        proof_paths = [
            adapter["selectionProofs"][unit]["path"] for unit in adapter["selection"]
        ]
        evidence = links(
            list(dict.fromkeys(adapter["fixtures"] + proof_paths + adapter["gates"]))
        )
        lines.append(
            f"| {markdown(adapter['ecosystem'])} | {markdown(', '.join(adapter['languages']))} | "
            f"{markdown(', '.join(adapter['runners']))} | "
            f"{markdown(' or '.join(SELECTION_LABELS[unit] for unit in adapter['selection']))} | "
            f"{markdown(adapter['versions'])} | {evidence} |"
        )
    lines += [
        "",
        "The smaller selection unit is used only when the adapter proves a complete",
        "relationship. Otherwise **Affected** keeps the larger unit shown in the same row.",
        "",
        "## Operating systems",
        "",
        "| Operating system | Evidence level | Evidence |",
        "|---|---|---|",
    ]
    os_levels = {"native": "Native fixtures", "contract": "Cross-platform contracts"}
    for system in systems:
        evidence = links(system["fixtures"] + system["gates"])
        lines.append(
            f"| {markdown(system['name'])} | {os_levels[system['support']]} | {evidence} |"
        )
    if excluded_products:
        lines += [
            "",
            "## Explicit exclusions",
            "",
            "| Product | Reason | Last reviewed |",
            "|---|---|---:|",
        ]
        for product in excluded_products:
            lines.append(
                f"| {markdown(product['name'])} | {markdown(product['reason'])} | {product['reviewed']} |"
            )
    lines += [
        "",
        "## Keep the matrix current",
        "",
        "Run `python3 scripts/support_matrix.py --check`. Use `--write` after an",
        "intentional matrix change, then add or update the public fixture and CI gate in",
        "the same pull request.",
        "",
    ]
    return "\n".join(lines)


def readme_summary(matrix: dict[str, Any]) -> str:
    """Render the compact README pointer derived from matrix counts."""
    products = sum(
        product["support"] in {"verified", "platform"} for product in matrix["products"]
    )
    adapters = len(matrix["adapters"])
    return (
        f"Built for multi-module projects and monorepos across {adapters} supported build "
        f"ecosystems and {products} JetBrains products. See the [support matrix](SUPPORT.md) "
        "for languages, test runners, selection units and evidence."
    )


def plugin_summary(matrix: dict[str, Any]) -> str:
    """Render the compact Marketplace pointer derived from matrix counts."""
    products = sum(
        product["support"] in {"verified", "platform"} for product in matrix["products"]
    )
    adapters = len(matrix["adapters"])
    return (
        f"        <p>Built for multi-module projects and monorepos across {adapters} supported build "
        f"ecosystems and {products} JetBrains products.</p>\n"
        '        <p><a href="https://github.com/aspix2k/affected/blob/main/SUPPORT.md">'
        "Support matrix</a>: languages, test runners, selection units and evidence.</p>"
    )


def replace_summary(content: str, summary: str, path: Path) -> str:
    """Replace exactly one generated summary block in a documentation file."""
    if content.count(SUMMARY_START) != 1 or content.count(SUMMARY_END) != 1:
        raise SupportMatrixError(f"Expected one generated support summary in {path}")
    before, remainder = content.split(SUMMARY_START, 1)
    _, after = remainder.split(SUMMARY_END, 1)
    return f"{before}{SUMMARY_START}\n{summary}\n{SUMMARY_END}{after}"


def expected_outputs(root: Path, matrix: dict[str, Any]) -> dict[Path, str]:
    """Build every generated support document from one validated matrix."""
    readme = replace_summary(
        read_file(root, README_PATH), readme_summary(matrix), README_PATH
    )
    plugin = replace_summary(
        read_file(root, PLUGIN_PATH), plugin_summary(matrix), PLUGIN_PATH
    )
    return {SUPPORT_PATH: render(matrix), README_PATH: readme, PLUGIN_PATH: plugin}


def write_atomic(path: Path, content: str) -> None:
    """Atomically replace one writable UTF-8 generated file."""
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and (path.is_symlink() or not os.access(path, os.W_OK)):
        raise SupportMatrixError(f"Generated file is not safely writable: {path}")
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except OSError as error:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise SupportMatrixError(f"Cannot write {path}: {error}") from error


def check(root: Path = ROOT) -> None:
    """Fail when registrations, evidence, or generated documents have drifted."""
    matrix = validated(root, load_matrix(root))
    for relative, expected in expected_outputs(root, matrix).items():
        actual = (
            read_file(root, relative) if secure_path(root, relative).exists() else ""
        )
        if actual != expected:
            raise SupportMatrixError(
                f"{relative} is stale; run scripts/support_matrix.py --write"
            )


def write(root: Path = ROOT) -> None:
    """Validate the matrix and atomically refresh every generated document."""
    matrix = validated(root, load_matrix(root))
    for relative, content in expected_outputs(root, matrix).items():
        write_atomic(secure_path(root, relative), content)


def parse_args(arguments: list[str] | None = None) -> argparse.Namespace:
    """Parse the single explicit check or write operation."""
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument(
        "--check",
        action="store_true",
        help="validate registrations, evidence, and generated docs",
    )
    mode.add_argument(
        "--write", action="store_true", help="refresh generated docs after validation"
    )
    return parser.parse_args(arguments)


def main(arguments: list[str] | None = None) -> int:
    """Run the selected operation with concise human-readable errors."""
    try:
        options = parse_args(arguments)
        if options.write:
            write()
            print("Updated executable support documentation.")
        else:
            check()
            print("Support matrix is complete and current.")
        return 0
    except SupportMatrixError as error:
        print(f"Support matrix error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
