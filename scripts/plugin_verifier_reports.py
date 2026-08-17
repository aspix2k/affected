#!/usr/bin/env python3

"""Validate product-specific Plugin Verifier inputs and reports fail closed."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

MAX_METADATA_BYTES = 64 * 1024
MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
MAX_VERDICT_BYTES = 4 * 1024
MAX_DEPENDENCIES_BYTES = 4 * 1024 * 1024
GIT_OBJECT_ID = re.compile(r"[0-9a-f]{40}")


class PluginVerifierReportError(RuntimeError):
    """Describe an invalid promoted plugin artifact or verifier report."""


def read_regular_text(path: Path, limit: int, label: str) -> str:
    """Read one bounded regular UTF-8 file without following a symlink."""
    try:
        if path.is_symlink() or not path.is_file():
            raise PluginVerifierReportError(f"Expected regular {label}")
        if path.stat().st_size > limit:
            raise PluginVerifierReportError(f"{label.capitalize()} exceeds size limit")
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        raise PluginVerifierReportError(f"Cannot read {label}: {error}") from error


def bounded_entries(directory: Path, limit: int, label: str) -> list[Path]:
    """Return at most the declared number of direct directory entries."""
    try:
        if directory.is_symlink() or not directory.is_dir():
            raise PluginVerifierReportError(f"Expected regular {label} directory")
        entries: list[Path] = []
        for entry in directory.iterdir():
            entries.append(entry)
            if len(entries) > limit:
                raise PluginVerifierReportError(f"Too many entries in {label}")
        return entries
    except OSError as error:
        raise PluginVerifierReportError(f"Cannot enumerate {label}: {error}") from error


def require_regular_directory(path: Path, label: str) -> None:
    """Require one directory path component without following a symlink."""
    if path.is_symlink() or not path.is_dir():
        raise PluginVerifierReportError(f"Expected regular {label} directory")


def dependency_state(text: str, plugin_id: str) -> str:
    """Return the unique direct optional dependency state from a verifier report."""
    states: list[str] = []
    present = (f"{plugin_id}:", f"(optional) {plugin_id}:")
    unavailable = f"(failed) {plugin_id} (optional):"
    for line in text.splitlines():
        if not (line.startswith("+--- ") or line.startswith("\\--- ")):
            continue
        dependency = line[5:]
        if dependency.startswith(present):
            states.append("present")
        elif dependency.startswith(unavailable) and dependency != unavailable:
            states.append("unavailable")
    if len(states) != 1:
        raise PluginVerifierReportError(
            f"Expected one direct {plugin_id} dependency state"
        )
    return states[0]


def sha256_file(path: Path) -> str:
    """Hash one bounded archive without loading it into memory."""
    digest = hashlib.sha256()
    try:
        if path.stat().st_size > MAX_ARCHIVE_BYTES:
            raise PluginVerifierReportError("Plugin archive exceeds size limit")
        with path.open("rb") as stream:
            while chunk := stream.read(1024 * 1024):
                digest.update(chunk)
    except OSError as error:
        raise PluginVerifierReportError(
            f"Cannot hash plugin archive: {error}"
        ) from error
    return digest.hexdigest()


def validate_artifact(
    directory: Path, expected_commit: str, expected_tree: str
) -> Path:
    """Return the plugin ZIP after matching its recorded digest and source identity."""
    metadata_path = directory / "release-metadata.json"
    try:
        metadata = json.loads(
            read_regular_text(metadata_path, MAX_METADATA_BYTES, "release metadata")
        )
    except json.JSONDecodeError as error:
        raise PluginVerifierReportError(
            f"Invalid release metadata JSON: {error}"
        ) from error
    expected_fields = {"version", "archive", "sha256", "sourceCommit", "sourceTree"}
    if not isinstance(metadata, dict) or set(metadata) != expected_fields:
        raise PluginVerifierReportError("Invalid release metadata fields")
    identities = (
        expected_commit,
        expected_tree,
        metadata.get("sourceCommit"),
        metadata.get("sourceTree"),
    )
    if any(
        not isinstance(value, str) or GIT_OBJECT_ID.fullmatch(value) is None
        for value in identities
    ):
        raise PluginVerifierReportError("Invalid full source identity")
    if (
        metadata.get("sourceCommit") != expected_commit
        or metadata.get("sourceTree") != expected_tree
    ):
        raise PluginVerifierReportError("Plugin source identity does not match")
    archive_name = metadata.get("archive")
    if (
        not isinstance(archive_name, str)
        or Path(archive_name).name != archive_name
        or not archive_name.endswith(".zip")
    ):
        raise PluginVerifierReportError("Invalid plugin archive name")
    archive = directory / archive_name
    if not archive.is_file() or archive.is_symlink():
        raise PluginVerifierReportError("Expected one regular plugin archive")
    entries = [path.name for path in bounded_entries(directory, 3, "artifact")]
    if sorted(entries) != sorted(["release-metadata.json", archive_name]):
        raise PluginVerifierReportError(
            "Expected exactly one plugin archive and metadata"
        )
    digest = sha256_file(archive)
    if digest != metadata.get("sha256"):
        raise PluginVerifierReportError("Plugin archive SHA-256 does not match")
    return archive.resolve()


def validate_report(
    directory: Path,
    expected_code: str,
    expected_build: str,
    expected_gradle: str,
    expected_maven: str,
) -> Path:
    """Validate one compatible product report and its optional descriptor states."""
    ide_directories = bounded_entries(directory, 1, "verifier report")
    if len(ide_directories) != 1:
        raise PluginVerifierReportError("Expected one Affected verifier report")
    ide = ide_directories[0]
    require_regular_directory(ide, "product report")
    if ide.name != f"{expected_code}-{expected_build}":
        raise PluginVerifierReportError("Verifier report does not match the product")
    plugins = ide / "plugins"
    require_regular_directory(plugins, "plugins report")
    plugin_root = plugins / "com.aspix2k.affected"
    require_regular_directory(plugin_root, "Affected plugin report")
    plugin_versions = bounded_entries(plugin_root, 1, "Affected plugin report")
    if len(plugin_versions) != 1:
        raise PluginVerifierReportError("Expected one Affected plugin report")
    require_regular_directory(plugin_versions[0], "Affected plugin version report")
    verdict = read_regular_text(
        plugin_versions[0] / "verification-verdict.txt",
        MAX_VERDICT_BYTES,
        "verification verdict",
    )
    if verdict != "Compatible\n":
        raise PluginVerifierReportError("Verifier report has a non-compatible verdict")
    dependencies = read_regular_text(
        plugin_versions[0] / "dependencies.txt",
        MAX_DEPENDENCIES_BYTES,
        "dependency report",
    )
    if dependency_state(dependencies, "com.intellij.gradle") != expected_gradle:
        raise PluginVerifierReportError(
            "Gradle descriptor state does not match support"
        )
    if dependency_state(dependencies, "org.jetbrains.idea.maven") != expected_maven:
        raise PluginVerifierReportError("Maven descriptor state does not match support")
    return ide.resolve()


def main(argv: list[str] | None = None) -> int:
    """Validate a promoted artifact selected by command-line arguments."""
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    artifact = subparsers.add_parser("artifact")
    artifact.add_argument("--directory", type=Path, required=True)
    artifact.add_argument("--commit", required=True)
    artifact.add_argument("--tree", required=True)
    report = subparsers.add_parser("report")
    report.add_argument("--directory", type=Path, required=True)
    report.add_argument("--code", required=True)
    report.add_argument("--build", required=True)
    report.add_argument("--gradle", choices=("present", "unavailable"), required=True)
    report.add_argument("--maven", choices=("present", "unavailable"), required=True)
    args = parser.parse_args(argv)
    if args.command == "artifact":
        print(validate_artifact(args.directory, args.commit, args.tree))
    elif args.command == "report":
        print(
            validate_report(
                args.directory,
                args.code,
                args.build,
                args.gradle,
                args.maven,
            )
        )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except PluginVerifierReportError as error:
        print(f"plugin-verifier-reports: ERROR: {error}", file=sys.stderr)
        raise SystemExit(1) from error
