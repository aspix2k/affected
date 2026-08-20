"""Validate and render Affected's executable support matrix."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
MATRIX_PATH = Path("config/support-matrix.json")
SUPPORT_PATH = Path("docs/SUPPORT.md")
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
IDE_VERSION = re.compile(r"\d{4}\.\d+(?:\.\d+){0,2}")
IDE_BUILD = re.compile(r"\d+(?:\.\d+){1,3}")
WORKFLOW_PATH = re.compile(r"\.github/workflows/[A-Za-z0-9][A-Za-z0-9_.-]*\.yml")
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
PLATFORM_VERIFIERS = {
    "rider": ("Rider", "RD"),
    "goland": ("GoLand", "GO"),
    "clion": ("CLion", "CL"),
    "pycharm": ("PyCharm", "PY"),
    "webstorm": ("WebStorm", "WS"),
    "phpstorm": ("PhpStorm", "PS"),
    "rubymine": ("RubyMine", "RM"),
    "rustrover": ("RustRover", "RR"),
    "dataspell": ("DataSpell", "DS"),
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


def require_fields(value: dict[str, Any], allowed: set[str], field: str) -> None:
    """Reject undeclared fields so support claims cannot grow implicitly."""
    unknown = sorted(set(value) - allowed)
    if unknown:
        raise SupportMatrixError(f"Unexpected {field} fields: {unknown}")


def require_reviewed_date(value: object, field: str) -> str:
    """Require a calendar-valid review date that is not in the future."""
    reviewed = require_string(value, field, DATE)
    try:
        reviewed_date = date.fromisoformat(reviewed)
    except ValueError as error:
        raise SupportMatrixError(f"Invalid calendar {field}: {reviewed}") from error
    if reviewed_date > datetime.now(timezone.utc).date():
        raise SupportMatrixError(f"Future {field}: {reviewed}")
    return reviewed


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


def without_yaml_comment(value: str) -> str:
    """Return the supported scalar value without a trailing YAML-style comment."""
    return value.split(" #", 1)[0].strip()


def disabled_condition(value: str) -> bool:
    """Recognize only literal and unconditional false workflow conditions."""
    normalized = without_yaml_comment(value).replace(" ", "").lower()
    expression = normalized.removeprefix("$" + "{{").removesuffix("}}")
    return expression in {
        "false",
        "1==0",
        "0==1",
        "always()&&false",
        "false&&always()",
    }


def scoped_proof_condition(value: str) -> bool:
    """Allow only a fail-closed ci_scope proof output as a job or step if."""
    return without_yaml_comment(value) in {
        "needs.scope.outputs.exact == 'true'",
        "needs.scope.outputs.plugin == 'true'",
    }


def supported_workflow_jobs(workflow: str, field: str) -> dict[str, dict[str, Any]]:
    """Parse the bounded job and step shape used by this repository's workflows."""
    if re.search(r"(?m)^on:(?:\s+\S.*)?$", workflow) is None:
        raise SupportMatrixError(f"Evidence gate has no trigger for {field}")
    if workflow.count("\njobs:\n") + workflow.startswith("jobs:\n") != 1:
        raise SupportMatrixError(
            f"Evidence gate must declare exactly one jobs mapping for {field}"
        )

    jobs: dict[str, dict[str, Any]] = {}
    job: dict[str, Any] | None = None
    step: dict[str, Any] | None = None
    lines = workflow.splitlines()
    index = 0
    while index < len(lines):
        line = lines[index]
        job_match = re.fullmatch(r"  ([A-Za-z0-9_-]+):\s*(?:#.*)?", line)
        if job_match is not None:
            name = job_match.group(1)
            if name in jobs:
                raise SupportMatrixError(
                    f"Evidence gate has duplicate job {name} for {field}"
                )
            job = {"name": name, "condition": None, "steps": []}
            jobs[name] = job
            step = None
            index += 1
            continue
        if job is None:
            index += 1
            continue
        job_if = re.fullmatch(r"    if:\s*(.+)", line)
        if job_if is not None:
            job["condition"] = job_if.group(1)
            index += 1
            continue
        step_match = re.fullmatch(r"      - (?:name:\s*(.+)|(run|uses):\s*(.+))", line)
        if step_match is not None:
            step = {"name": step_match.group(1), "condition": None}
            if step_match.group(2) is not None:
                step[step_match.group(2)] = without_yaml_comment(step_match.group(3))
            job["steps"].append(step)
            index += 1
            continue
        if step is None:
            index += 1
            continue
        step_if = re.fullmatch(r"        if:\s*(.+)", line)
        if step_if is not None:
            step["condition"] = step_if.group(1)
            index += 1
            continue
        executable = re.fullmatch(r"        (run|uses):\s*(.+)", line)
        if executable is None:
            index += 1
            continue
        kind, value = executable.groups()
        if kind == "uses" or value not in {"|", "|-", ">", ">-"}:
            step[kind] = without_yaml_comment(value)
            index += 1
            continue
        block: list[str] = []
        index += 1
        while index < len(lines):
            block_line = lines[index]
            if block_line and not block_line.startswith("          "):
                break
            if block_line.lstrip().startswith("#"):
                index += 1
                continue
            block.append(without_yaml_comment(block_line[10:]))
            index += 1
        step["run"] = "\n".join(block)
    if not jobs:
        raise SupportMatrixError(f"Evidence gate has no jobs for {field}")
    return jobs


def require_workflow(root: Path, text: str, field: str) -> dict[str, dict[str, Any]]:
    """Require one executable GitHub Actions workflow at the declared gate path."""
    if WORKFLOW_PATH.fullmatch(text) is None:
        raise SupportMatrixError(
            f"Evidence gate must be a .github workflow for {field}: {text}"
        )
    relative = Path(text)
    path = secure_path(root, relative)
    if not path.is_file() or path.is_symlink():
        raise SupportMatrixError(
            f"Evidence gate must be a regular file for {field}: {text}"
        )
    jobs = supported_workflow_jobs(read_file(root, relative), field)
    if not any(
        not disabled_condition(job["condition"] or "")
        and any(
            not disabled_condition(step["condition"] or "")
            and ("run" in step or "uses" in step)
            for step in job["steps"]
        )
        for job in jobs.values()
    ):
        raise SupportMatrixError(
            f"Evidence gate has no executable step for {field}: {text}"
        )
    return jobs


def require_proof_execution(
    root: Path,
    gate: str,
    job_name: str,
    step_name: str,
    marker: str,
    field: str,
) -> None:
    """Bind a selection proof marker to one enabled executable workflow step."""
    jobs = require_workflow(root, gate, field)
    job = jobs.get(job_name)
    if job is None:
        raise SupportMatrixError(f"{field} gate job is missing: {job_name}")
    if job["condition"] is not None and not scoped_proof_condition(job["condition"]):
        state = "disabled" if disabled_condition(job["condition"]) else "conditional"
        raise SupportMatrixError(f"{field} gate job is {state}: {job_name}")
    steps = [step for step in job["steps"] if step["name"] == step_name]
    if len(steps) != 1:
        raise SupportMatrixError(
            f"{field} gate step must occur exactly once: {step_name}"
        )
    step = steps[0]
    if step["condition"] is not None and not scoped_proof_condition(step["condition"]):
        state = "disabled" if disabled_condition(step["condition"]) else "conditional"
        raise SupportMatrixError(f"{field} gate step is {state}: {step_name}")
    executable = step.get("run") or step.get("uses")
    if not isinstance(executable, str) or executable.count(marker) != 1:
        raise SupportMatrixError(
            f"{field} gate marker must occur exactly once in {job_name}/{step_name}: {marker}"
        )


def require_gates(root: Path, value: object, field: str) -> list[str]:
    """Validate every declared evidence gate as an executable GitHub workflow."""
    gates = require_strings(value, field)
    for gate in gates:
        require_workflow(root, gate, field)
    return gates


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
            allowed = {"id", "name", "support", "reason", "reviewed"}
            if support == "planned":
                allowed.add("issue")
            require_fields(product, allowed, "product")
            reason = require_string(product.get("reason"), f"{identifier} reason")
            reviewed = require_reviewed_date(
                product.get("reviewed"), f"{identifier} reviewed"
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
            allowed = {"id", "name", "support", "since", "homeAdapters", "fixtures", "gates"}
            if support == "platform":
                allowed.add("verifier")
            require_fields(
                product,
                allowed,
                "product",
            )
            since = require_string(product.get("since"), f"{identifier} since")
            if support == "platform" and not isinstance(product.get("verifier"), dict):
                raise SupportMatrixError(
                    f"{identifier} platform claim requires verifier endpoints"
                )
            if support == "platform":
                verifier = product["verifier"]
                require_fields(verifier, {"type", "endpoints"}, "product verifier")
                expected = PLATFORM_VERIFIERS.get(identifier)
                if expected is None or verifier.get("type") != expected[0]:
                    raise SupportMatrixError(
                        f"Invalid verifier product type for {identifier}"
                    )
                endpoints = verifier.get("endpoints")
                endpoint_ids = (
                    [endpoint.get("id") for endpoint in endpoints]
                    if isinstance(endpoints, list)
                    and all(isinstance(endpoint, dict) for endpoint in endpoints)
                    else []
                )
                if endpoint_ids != ["minimum", "current"]:
                    raise SupportMatrixError(
                        f"{identifier} verifier must define minimum and current endpoints"
                    )
                for endpoint in endpoints:
                    require_fields(
                        endpoint,
                        {"id", "version", "build", "gradle", "maven"},
                        "verifier endpoint",
                    )
                    version = require_string(
                        endpoint.get("version"),
                        f"{identifier} {endpoint['id']} verifier version",
                        IDE_VERSION,
                    )
                    if endpoint["id"] == "minimum" and not (
                        version == since or version.startswith(f"{since}.")
                    ):
                        raise SupportMatrixError(
                            f"Invalid minimum verifier version for {identifier}: {version}"
                        )
                    require_string(
                        endpoint.get("build"),
                        f"{identifier} {endpoint['id']} verifier build",
                        IDE_BUILD,
                    )
                    for dependency in ("gradle", "maven"):
                        state = require_string(
                            endpoint.get(dependency),
                            f"{identifier} {endpoint['id']} {dependency} descriptor state",
                        )
                        if state not in {"present", "unavailable"}:
                            raise SupportMatrixError(
                                f"Invalid descriptor state for {identifier} {endpoint['id']} {dependency}: {state}"
                            )
                minimum_parts = tuple(
                    int(part) for part in endpoints[0]["version"].split(".")
                )
                current_parts = tuple(
                    int(part) for part in endpoints[1]["version"].split(".")
                )
                minimum_version = minimum_parts + (0,) * (4 - len(minimum_parts))
                current_version = current_parts + (0,) * (4 - len(current_parts))
                if current_version < minimum_version:
                    raise SupportMatrixError(
                        f"Invalid current verifier version for {identifier}: {endpoints[1]['version']}"
                    )
            product["since"] = since
            product["homeAdapters"] = [
                require_string(item, f"{identifier} homeAdapters", ADAPTER_IDENTIFIER)
                for item in require_strings(
                    product.get("homeAdapters"), f"{identifier} homeAdapters"
                )
            ]
            product["fixtures"] = require_evidence(
                root, product.get("fixtures"), f"{identifier} fixtures"
            )
            product["gates"] = require_gates(
                root, product.get("gates"), f"{identifier} gates"
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
        require_fields(
            system,
            {"id", "name", "support", "fixtures", "gates"},
            "operating system",
        )
        require_string(system.get("name"), f"{identifier} name")
        level = require_string(system.get("support"), f"{identifier} support")
        if level not in {"native", "contract"}:
            raise SupportMatrixError(
                f"Unsupported operating system level for {identifier}: {level}"
            )
        system["fixtures"] = require_evidence(
            root, system.get("fixtures"), f"{identifier} fixtures"
        )
        system["gates"] = require_gates(
            root, system.get("gates"), f"{identifier} gates"
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
        require_fields(
            adapter,
            {
                "id",
                "implementation",
                "ecosystem",
                "languages",
                "runners",
                "selection",
                "selectionProofs",
                "versions",
                "support",
                "fixtures",
                "gates",
            },
            "adapter",
        )
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
        adapter["gates"] = require_gates(
            root, adapter.get("gates"), f"{identifier} gates"
        )
        proofs = adapter.get("selectionProofs")
        if not isinstance(proofs, list) or not proofs or len(proofs) > MAX_EVIDENCE:
            raise SupportMatrixError(
                f"{identifier} selection proofs must be a bounded list"
            )
        normalized_proofs: list[dict[str, Any]] = []
        covered_units: set[str] = set()
        covered_runner_units: set[tuple[str | None, str]] = set()
        runner_scoped: bool | None = None
        for proof in proofs:
            if not isinstance(proof, dict):
                raise SupportMatrixError(f"Invalid {identifier} selection proof")
            require_fields(
                proof,
                {
                    "units",
                    "path",
                    "marker",
                    "gate",
                    "gateJob",
                    "gateStep",
                    "gateMarker",
                    "runner",
                },
                "selection proof",
            )
            runner = (
                require_string(
                    proof.get("runner"), f"{identifier} selection proof runner"
                )
                if "runner" in proof
                else None
            )
            if runner is not None and runner not in adapter["runners"]:
                raise SupportMatrixError(
                    f"{identifier} selection proof runner must be declared: {runner}"
                )
            scoped = runner is not None
            if runner_scoped is None:
                runner_scoped = scoped
            elif runner_scoped != scoped:
                raise SupportMatrixError(
                    f"{identifier} selection proofs must all declare runner or all omit it"
                )
            units = require_strings(
                proof.get("units"), f"{identifier} selection proof units"
            )
            runner_units = {(runner, unit) for unit in units}
            if set(units) - set(
                adapter["selection"]
            ) or covered_runner_units.intersection(runner_units):
                raise SupportMatrixError(
                    f"{identifier} selection proofs must exactly match its selection units"
                )
            covered_units.update(units)
            covered_runner_units.update(runner_units)
            path = require_evidence(
                root,
                [proof.get("path")],
                f"{identifier} selection proof",
                regular_files=True,
            )[0]
            marker = require_string(
                proof.get("marker"), f"{identifier} selection marker"
            )
            occurrences = read_file(root, Path(path)).count(marker)
            if occurrences != 1:
                raise SupportMatrixError(
                    f"{identifier} selection marker must occur exactly once: {marker}"
                )
            gate = require_string(
                proof.get("gate"), f"{identifier} selection proof gate"
            )
            if gate not in adapter["gates"]:
                raise SupportMatrixError(
                    f"{identifier} selection proof gate must be one of its gates: {gate}"
                )
            gate_job = require_string(
                proof.get("gateJob"), f"{identifier} selection proof gate job"
            )
            gate_step = require_string(
                proof.get("gateStep"), f"{identifier} selection proof gate step"
            )
            gate_marker = require_string(
                proof.get("gateMarker"), f"{identifier} selection proof gate marker"
            )
            require_proof_execution(
                root,
                gate,
                gate_job,
                gate_step,
                gate_marker,
                f"{identifier} selection proof",
            )
            normalized_proof = {
                "units": units,
                "path": path,
                "marker": marker,
                "gate": gate,
                "gateJob": gate_job,
                "gateStep": gate_step,
                "gateMarker": gate_marker,
            }
            if runner is not None:
                normalized_proof["runner"] = runner
            normalized_proofs.append(normalized_proof)
        if covered_units != set(adapter["selection"]):
            raise SupportMatrixError(
                f"{identifier} selection proofs must exactly match its selection units"
            )
        adapter["selectionProofs"] = normalized_proofs
        adapter["fixtures"] = require_evidence(
            root, adapter.get("fixtures"), f"{identifier} fixtures"
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


def bind_home_adapters(
    products: list[dict[str, Any]], adapters: list[dict[str, Any]]
) -> None:
    """Require every claimed product to own at least one proven native adapter."""
    by_id = {adapter["id"]: adapter for adapter in adapters}
    for product in products:
        if product["support"] not in {"verified", "platform"}:
            continue
        for adapter_id in product["homeAdapters"]:
            adapter = by_id.get(adapter_id)
            if adapter is None:
                raise SupportMatrixError(
                    f"{product['id']} home adapter is missing: {adapter_id}"
                )
            if adapter["support"] != "supported":
                raise SupportMatrixError(
                    f"{product['id']} home adapter is not supported: {adapter_id}"
                )
            if not adapter["fixtures"] or not adapter["gates"]:
                raise SupportMatrixError(
                    f"{product['id']} home adapter lacks runtime evidence: {adapter_id}"
                )


def bind_home_mixed_proofs(
    products: list[dict[str, Any]], mixed_proofs: list[dict[str, Any]]
) -> None:
    """Require a mixed proof among the home adapters of every multi-home product."""
    for product in products:
        if product["support"] not in {"verified", "platform"}:
            continue
        homes = set(product["homeAdapters"])
        if len(homes) < 2:
            continue
        if not any(
            len(set(proof["adapters"])) >= 2 and set(proof["adapters"]) <= homes
            for proof in mixed_proofs
        ):
            raise SupportMatrixError(
                f"{product['id']} has multiple home adapters but no mixed proof among them"
            )


def validate_mixed_proofs(
    root: Path, value: object, adapters: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    """Require mixed-build-system fixtures to name real adapters and tests."""
    if not isinstance(value, list) or not value or len(value) > 16:
        raise SupportMatrixError("mixedProofs must be a non-empty bounded list")
    by_id = {adapter["id"]: adapter for adapter in adapters}
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for raw in value:
        if not isinstance(raw, dict):
            raise SupportMatrixError("Every mixed proof must be an object")
        proof = dict(raw)
        require_fields(proof, {"id", "adapters", "fixtures", "tests"}, "mixed proof")
        identifier = require_string(proof.get("id"), "mixed proof id", IDENTIFIER)
        if identifier in seen:
            raise SupportMatrixError(f"Duplicate mixed proof: {identifier}")
        seen.add(identifier)
        adapter_ids = [
            require_string(item, f"{identifier} adapters", ADAPTER_IDENTIFIER)
            for item in require_strings(proof.get("adapters"), f"{identifier} adapters")
        ]
        if len(set(adapter_ids)) < 2:
            raise SupportMatrixError(
                f"{identifier} mixed proof must name at least two distinct adapters"
            )
        for adapter_id in adapter_ids:
            if adapter_id not in by_id:
                raise SupportMatrixError(
                    f"{identifier} mixed adapter is missing: {adapter_id}"
                )
        proof["id"] = identifier
        proof["adapters"] = adapter_ids
        proof["fixtures"] = require_evidence(
            root, proof.get("fixtures"), f"{identifier} fixtures"
        )
        proof["tests"] = require_evidence(root, proof.get("tests"), f"{identifier} tests")
        result.append(proof)
    return result


def validated(root: Path, matrix: dict[str, Any]) -> dict[str, Any]:
    """Return a normalized matrix after all fail-closed checks pass."""
    allowed = {"schema", "products", "operatingSystems", "adapters", "mixedProofs"}
    if set(matrix) != allowed:
        raise SupportMatrixError(
            f"Unexpected support matrix fields: {sorted(set(matrix) - allowed)}"
        )
    products = validate_products(root, matrix.get("products"))
    operating_systems = validate_operating_systems(
        root, matrix.get("operatingSystems")
    )
    adapters = validate_adapters(root, matrix.get("adapters"))
    bind_home_adapters(products, adapters)
    mixed_proofs = validate_mixed_proofs(root, matrix.get("mixedProofs"), adapters)
    bind_home_mixed_proofs(products, mixed_proofs)
    return {
        "schema": 1,
        "products": products,
        "operatingSystems": operating_systems,
        "adapters": adapters,
        "mixedProofs": mixed_proofs,
    }


def verifier_matrix(matrix: dict[str, Any]) -> list[dict[str, str]]:
    """Expand validated platform claims into deterministic CI verifier cells."""
    result: list[dict[str, str]] = []
    for product in matrix["products"]:
        if product["support"] != "platform":
            continue
        verifier = product["verifier"]
        for endpoint in verifier["endpoints"]:
            result.append(
                {
                    "product": product["id"],
                    "type": verifier["type"],
                    "code": PLATFORM_VERIFIERS[product["id"]][1],
                    "endpoint": endpoint["id"],
                    "version": endpoint["version"],
                    "build": endpoint["build"],
                    "gradle": endpoint["gradle"],
                    "maven": endpoint["maven"],
                }
            )
    return result


def markdown(text: str) -> str:
    """Escape table separators and line breaks in generated Markdown cells."""
    return text.replace("|", "\\|").replace("\n", " ")


def links(paths: list[str], origin: Path = SUPPORT_PATH) -> str:
    """Render concise Markdown links relative to the generated support page."""
    return " · ".join(
        f"[{Path(path).name}]({Path(os.path.relpath(path, start=origin.parent)).as_posix()})"
        for path in paths
    )


def render(matrix: dict[str, Any], mcp_section: str = "") -> str:
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
        "| Product | Evidence level | Minimum platform | Home ecosystems | Evidence |",
        "|---|---|---:|---|---|",
    ]
    product_levels = {"verified": "Product-verified", "platform": "Platform-compatible"}
    adapters_by_id = {adapter["id"]: adapter for adapter in adapters}
    for product in supported_products:
        homes = ", ".join(
            adapters_by_id[adapter_id]["ecosystem"]
            for adapter_id in product["homeAdapters"]
        )
        evidence = links(product["fixtures"] + product["gates"])
        lines.append(
            f"| {markdown(product['name'])} | {product_levels[product['support']]} | "
            f"{markdown(product['since'])} | {markdown(homes)} | {evidence} |"
        )
    lines += [
        "",
        "Product-verified entries run a dedicated product gate. Every Platform-compatible",
        "entry runs a static product-specific Plugin Verifier at its exact minimum and",
        "current endpoints, including the declared optional Gradle and Maven descriptors.",
        "Home ecosystems are the native adapters that the product must keep proven at runtime;",
        "Plugin Verifier still does not claim the installed IDE lifecycle.",
    ]
    mixed_proofs = matrix.get("mixedProofs") or []
    if mixed_proofs:
        lines += [
            "",
            "## Mixed build systems",
            "",
            "| Proof | Adapters | Evidence |",
            "|---|---|---|",
        ]
        for proof in mixed_proofs:
            names = ", ".join(
                adapters_by_id[adapter_id]["ecosystem"]
                for adapter_id in proof["adapters"]
            )
            evidence = links(proof["fixtures"] + proof["tests"])
            lines.append(
                f"| {markdown(proof['id'])} | {markdown(names)} | {evidence} |"
            )
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
        proof_paths = [proof["path"] for proof in adapter["selectionProofs"]]
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
    if mcp_section:
        lines += ["", mcp_section.strip(), ""]
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
        f"ecosystems and {products} JetBrains products. See the [support matrix](docs/SUPPORT.md) "
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
        '        <p><a href="https://github.com/aspix2k/affected/blob/main/docs/SUPPORT.md">'
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
    mcp_section = ""
    if secure_path(root, Path("config/mcp-capabilities.json")).is_file():
        from scripts.mcp_capabilities import load_matrix as load_mcp
        from scripts.mcp_capabilities import render_section
        from scripts.mcp_capabilities import validated as validate_mcp

        mcp_section = render_section(validate_mcp(root, load_mcp(root)))
    return {
        SUPPORT_PATH: render(matrix, mcp_section),
        README_PATH: readme,
        PLUGIN_PATH: plugin,
    }


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
    """Parse one explicit matrix operation and its repository root."""
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
    mode.add_argument(
        "--verifier-matrix",
        action="store_true",
        help="print the validated product verifier cells as compact JSON",
    )
    parser.add_argument("--root", type=Path, default=ROOT, help="repository root")
    return parser.parse_args(arguments)


def main(arguments: list[str] | None = None) -> int:
    """Run the selected operation with concise human-readable errors."""
    try:
        options = parse_args(arguments)
        if options.verifier_matrix:
            matrix = validated(options.root, load_matrix(options.root))
            print(json.dumps(verifier_matrix(matrix), separators=(",", ":")))
        elif options.write:
            write(options.root)
            print("Updated executable support documentation.")
        else:
            check(options.root)
            print("Support matrix is complete and current.")
        return 0
    except SupportMatrixError as error:
        print(f"Support matrix error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
