"""Tests for product-specific Plugin Verifier artifact and report validation."""

from __future__ import annotations

import importlib.util
import hashlib
import io
import json
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from tempfile import TemporaryDirectory

from scripts import plugin_verifier_reports


class PluginVerifierReportsTest(unittest.TestCase):
    """Exercise fail-closed verifier input and report boundaries."""

    def test_validator_module_is_repository_owned(self) -> None:
        """Keep CI verification logic in a tracked testable script."""
        self.assertIsNotNone(
            importlib.util.find_spec("scripts.plugin_verifier_reports")
        )

    def test_promoted_archive_matches_its_exact_source_metadata(self) -> None:
        """Accept one regular plugin ZIP whose digest and source identity match."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "affected-plugin-3.15.1.zip"
            archive.write_bytes(b"verified plugin")
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            metadata = {
                "version": "3.15.1",
                "archive": archive.name,
                "sha256": digest,
                "sourceCommit": "a" * 40,
                "sourceTree": "b" * 40,
            }
            (root / "release-metadata.json").write_text(
                json.dumps(metadata), encoding="utf-8"
            )
            validator = getattr(plugin_verifier_reports, "validate_artifact", None)

            self.assertIsNotNone(validator)
            if validator is not None:
                self.assertEqual(archive.resolve(), validator(root, "a" * 40, "b" * 40))

    def test_artifact_cli_returns_only_the_validated_archive_path(self) -> None:
        """Expose provenance validation to CI without shell-side parsing."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "affected-plugin-3.15.1.zip"
            archive.write_bytes(b"verified plugin")
            metadata = {
                "version": "3.15.1",
                "archive": archive.name,
                "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
                "sourceCommit": "a" * 40,
                "sourceTree": "b" * 40,
            }
            (root / "release-metadata.json").write_text(
                json.dumps(metadata), encoding="utf-8"
            )
            output = io.StringIO()
            main = getattr(plugin_verifier_reports, "main", None)

            self.assertIsNotNone(main)
            if main is not None:
                with redirect_stdout(output):
                    result = main(
                        [
                            "artifact",
                            "--directory",
                            str(root),
                            "--commit",
                            "a" * 40,
                            "--tree",
                            "b" * 40,
                        ]
                    )
                self.assertEqual(0, result)
                self.assertEqual(f"{archive.resolve()}\n", output.getvalue())

    def test_promoted_archive_cannot_escape_its_download_directory(self) -> None:
        """Reject traversal even when the outside file matches the recorded digest."""
        with TemporaryDirectory() as directory:
            parent = Path(directory)
            root = parent / "download"
            root.mkdir()
            archive = parent / "outside.zip"
            archive.write_bytes(b"outside")
            metadata = {
                "version": "3.15.1",
                "archive": "../outside.zip",
                "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
                "sourceCommit": "a" * 40,
                "sourceTree": "b" * 40,
            }
            (root / "release-metadata.json").write_text(
                json.dumps(metadata), encoding="utf-8"
            )

            with self.assertRaisesRegex(
                plugin_verifier_reports.PluginVerifierReportError, "archive name"
            ):
                plugin_verifier_reports.validate_artifact(root, "a" * 40, "b" * 40)

    def test_promoted_archive_must_be_one_regular_owned_zip(self) -> None:
        """Reject a symlinked artifact even when its bytes match the metadata."""
        with TemporaryDirectory() as directory:
            parent = Path(directory)
            root = parent / "download"
            root.mkdir()
            outside = parent / "outside.zip"
            outside.write_bytes(b"outside")
            archive = root / "affected-plugin-3.15.1.zip"
            archive.symlink_to(outside)
            metadata = {
                "version": "3.15.1",
                "archive": archive.name,
                "sha256": hashlib.sha256(outside.read_bytes()).hexdigest(),
                "sourceCommit": "a" * 40,
                "sourceTree": "b" * 40,
            }
            (root / "release-metadata.json").write_text(
                json.dumps(metadata), encoding="utf-8"
            )

            with self.assertRaisesRegex(
                plugin_verifier_reports.PluginVerifierReportError,
                "regular plugin archive",
            ):
                plugin_verifier_reports.validate_artifact(root, "a" * 40, "b" * 40)

    def test_promoted_artifact_directory_rejects_an_extra_zip(self) -> None:
        """Reject ambiguous downloads instead of choosing the metadata target."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "affected-plugin-3.15.1.zip"
            archive.write_bytes(b"verified plugin")
            (root / "unrecorded.zip").write_bytes(b"other")
            metadata = {
                "version": "3.15.1",
                "archive": archive.name,
                "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
                "sourceCommit": "a" * 40,
                "sourceTree": "b" * 40,
            }
            (root / "release-metadata.json").write_text(
                json.dumps(metadata), encoding="utf-8"
            )

            with self.assertRaisesRegex(
                plugin_verifier_reports.PluginVerifierReportError, "one plugin archive"
            ):
                plugin_verifier_reports.validate_artifact(root, "a" * 40, "b" * 40)

    def test_promoted_artifact_metadata_rejects_unknown_fields(self) -> None:
        """Keep the provenance document closed to unvalidated identity inputs."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "affected-plugin-3.15.1.zip"
            archive.write_bytes(b"verified plugin")
            metadata = {
                "version": "3.15.1",
                "archive": archive.name,
                "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
                "sourceCommit": "a" * 40,
                "sourceTree": "b" * 40,
                "unreviewed": True,
            }
            (root / "release-metadata.json").write_text(
                json.dumps(metadata), encoding="utf-8"
            )

            with self.assertRaisesRegex(
                plugin_verifier_reports.PluginVerifierReportError, "metadata fields"
            ):
                plugin_verifier_reports.validate_artifact(root, "a" * 40, "b" * 40)

    def test_promoted_artifact_requires_regular_bounded_metadata(self) -> None:
        """Reject provenance metadata reached through a symlink."""
        with TemporaryDirectory() as directory:
            parent = Path(directory)
            root = parent / "download"
            root.mkdir()
            archive = root / "affected-plugin-3.15.1.zip"
            archive.write_bytes(b"verified plugin")
            metadata = {
                "version": "3.15.1",
                "archive": archive.name,
                "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
                "sourceCommit": "a" * 40,
                "sourceTree": "b" * 40,
            }
            outside = parent / "release-metadata.json"
            outside.write_text(json.dumps(metadata), encoding="utf-8")
            (root / "release-metadata.json").symlink_to(outside)

            with self.assertRaisesRegex(
                plugin_verifier_reports.PluginVerifierReportError, "metadata"
            ):
                plugin_verifier_reports.validate_artifact(root, "a" * 40, "b" * 40)

    def test_promoted_artifact_rejects_malformed_metadata(self) -> None:
        """Convert malformed provenance JSON into an actionable validation failure."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "release-metadata.json").write_text("{", encoding="utf-8")

            with self.assertRaisesRegex(
                plugin_verifier_reports.PluginVerifierReportError, "metadata JSON"
            ):
                plugin_verifier_reports.validate_artifact(root, "a" * 40, "b" * 40)

    def test_promoted_artifact_requires_full_source_object_ids(self) -> None:
        """Reject ambiguous source identities before comparing provenance."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "affected-plugin-3.15.1.zip"
            archive.write_bytes(b"verified plugin")
            metadata = {
                "version": "3.15.1",
                "archive": archive.name,
                "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
                "sourceCommit": "head",
                "sourceTree": "tree",
            }
            (root / "release-metadata.json").write_text(
                json.dumps(metadata), encoding="utf-8"
            )

            with self.assertRaisesRegex(
                plugin_verifier_reports.PluginVerifierReportError, "source identity"
            ):
                plugin_verifier_reports.validate_artifact(root, "head", "tree")

    def test_promoted_archive_has_a_hard_size_limit(self) -> None:
        """Reject an oversized promoted ZIP before loading or hashing all its bytes."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "affected-plugin-3.15.1.zip"
            with archive.open("wb") as stream:
                stream.truncate(32 * 1024 * 1024 + 1)
            metadata = {
                "version": "3.15.1",
                "archive": archive.name,
                "sha256": "0" * 64,
                "sourceCommit": "a" * 40,
                "sourceTree": "b" * 40,
            }
            (root / "release-metadata.json").write_text(
                json.dumps(metadata), encoding="utf-8"
            )

            with self.assertRaisesRegex(
                plugin_verifier_reports.PluginVerifierReportError, "size limit"
            ):
                plugin_verifier_reports.validate_artifact(root, "a" * 40, "b" * 40)

    def test_compatible_report_matches_product_and_optional_descriptor_states(
        self,
    ) -> None:
        """Accept one product report with the declared Gradle and Maven states."""
        with TemporaryDirectory() as directory:
            reports = Path(directory)
            ide = reports / "RD-253.33813.59"
            plugin = ide / "plugins/com.aspix2k.affected/3.15.1"
            plugin.mkdir(parents=True)
            (plugin / "verification-verdict.txt").write_text(
                "Compatible\n", encoding="utf-8"
            )
            (plugin / "dependencies.txt").write_text(
                "com.aspix2k.affected:3.15.1\n"
                "+--- (optional) com.intellij.gradle:253.33813.70\n"
                "+--- (failed) org.jetbrains.idea.maven (optional): Dependency "
                "was not resolved from the product repository\n"
                "\\--- com.intellij:253.33813.59\n",
                encoding="utf-8",
            )
            validator = getattr(plugin_verifier_reports, "validate_report", None)

            self.assertIsNotNone(validator)
            if validator is not None:
                self.assertEqual(
                    ide.resolve(),
                    validator(
                        reports,
                        "RD",
                        "253.33813.59",
                        "present",
                        "unavailable",
                    ),
                )

    def test_report_cli_returns_only_the_validated_product_path(self) -> None:
        """Expose exact product report validation to the matrix job."""
        with TemporaryDirectory() as directory:
            reports = Path(directory)
            ide = reports / "RD-253.33813.59"
            plugin = ide / "plugins/com.aspix2k.affected/3.15.1"
            plugin.mkdir(parents=True)
            (plugin / "verification-verdict.txt").write_text(
                "Compatible\n", encoding="utf-8"
            )
            (plugin / "dependencies.txt").write_text(
                "+--- com.intellij.gradle:253.33813.59\n"
                "+--- (failed) org.jetbrains.idea.maven (optional): Unavailable\n",
                encoding="utf-8",
            )
            output = io.StringIO()

            with redirect_stdout(output):
                result = plugin_verifier_reports.main(
                    [
                        "report",
                        "--directory",
                        str(reports),
                        "--code",
                        "RD",
                        "--build",
                        "253.33813.59",
                        "--gradle",
                        "present",
                        "--maven",
                        "unavailable",
                    ]
                )

            self.assertEqual(0, result)
            self.assertEqual(f"{ide.resolve()}\n", output.getvalue())

    def test_report_cannot_substitute_an_intellij_idea_result(self) -> None:
        """Reject a compatible report produced for a different product code."""
        with TemporaryDirectory() as directory:
            reports = Path(directory)
            ide = reports / "IU-253.33813.59"
            plugin = ide / "plugins/com.aspix2k.affected/3.15.1"
            plugin.mkdir(parents=True)
            (plugin / "verification-verdict.txt").write_text(
                "Compatible\n", encoding="utf-8"
            )
            (plugin / "dependencies.txt").write_text(
                "+--- com.intellij.gradle:253.33813.59\n"
                "+--- (failed) org.jetbrains.idea.maven (optional): Unavailable\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                plugin_verifier_reports.PluginVerifierReportError, "product"
            ):
                plugin_verifier_reports.validate_report(
                    reports, "RD", "253.33813.59", "present", "unavailable"
                )

    def test_report_requires_a_complete_product_build_identity(self) -> None:
        """Reject a product prefix that is not followed by a numeric build."""
        with TemporaryDirectory() as directory:
            reports = Path(directory)
            ide = reports / "RD-unbound"
            plugin = ide / "plugins/com.aspix2k.affected/3.15.1"
            plugin.mkdir(parents=True)
            (plugin / "verification-verdict.txt").write_text(
                "Compatible\n", encoding="utf-8"
            )
            (plugin / "dependencies.txt").write_text(
                "+--- com.intellij.gradle:253.33813.59\n"
                "+--- (failed) org.jetbrains.idea.maven (optional): Unavailable\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                plugin_verifier_reports.PluginVerifierReportError, "product"
            ):
                plugin_verifier_reports.validate_report(
                    reports, "RD", "253.33813.59", "present", "unavailable"
                )

    def test_report_cannot_substitute_a_different_endpoint_build(self) -> None:
        """Reject a minimum report presented as the product's current endpoint."""
        with TemporaryDirectory() as directory:
            reports = Path(directory)
            ide = reports / "RD-253.33813.59"
            plugin = ide / "plugins/com.aspix2k.affected/3.15.1"
            plugin.mkdir(parents=True)
            (plugin / "verification-verdict.txt").write_text(
                "Compatible\n", encoding="utf-8"
            )
            (plugin / "dependencies.txt").write_text(
                "+--- (optional) com.intellij.gradle:253.33813.70\n"
                "+--- (failed) org.jetbrains.idea.maven (optional): Unavailable\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                plugin_verifier_reports.PluginVerifierReportError, "product"
            ):
                plugin_verifier_reports.validate_report(
                    reports, "RD", "262.8665.400", "present", "unavailable"
                )

    def test_report_product_directory_must_not_be_a_symlink(self) -> None:
        """Reject verifier evidence reached through an external product directory."""
        with TemporaryDirectory() as directory:
            parent = Path(directory)
            reports = parent / "reports"
            outside = parent / "RD-253.33813.59"
            plugin = outside / "plugins/com.aspix2k.affected/3.15.1"
            plugin.mkdir(parents=True)
            (plugin / "verification-verdict.txt").write_text(
                "Compatible\n", encoding="utf-8"
            )
            (plugin / "dependencies.txt").write_text(
                "+--- com.intellij.gradle:253.33813.59\n"
                "+--- (failed) org.jetbrains.idea.maven (optional): Unavailable\n",
                encoding="utf-8",
            )
            reports.mkdir()
            (reports / outside.name).symlink_to(outside, target_is_directory=True)

            with self.assertRaisesRegex(
                plugin_verifier_reports.PluginVerifierReportError, "regular product"
            ):
                plugin_verifier_reports.validate_report(
                    reports, "RD", "253.33813.59", "present", "unavailable"
                )

    def test_report_requires_the_exact_compatible_verdict(self) -> None:
        """Reject a completed verifier report that contains compatibility problems."""
        with TemporaryDirectory() as directory:
            reports = Path(directory)
            ide = reports / "RD-253.33813.59"
            plugin = ide / "plugins/com.aspix2k.affected/3.15.1"
            plugin.mkdir(parents=True)
            (plugin / "verification-verdict.txt").write_text(
                "Not compatible\n", encoding="utf-8"
            )
            (plugin / "dependencies.txt").write_text(
                "+--- com.intellij.gradle:253.33813.59\n"
                "+--- (failed) org.jetbrains.idea.maven (optional): Unavailable\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                plugin_verifier_reports.PluginVerifierReportError, "verdict"
            ):
                plugin_verifier_reports.validate_report(
                    reports, "RD", "253.33813.59", "present", "unavailable"
                )

    def test_report_requires_the_declared_gradle_descriptor_state(self) -> None:
        """Reject a report whose direct Gradle descriptor state differs from support."""
        with TemporaryDirectory() as directory:
            reports = Path(directory)
            ide = reports / "RD-253.33813.59"
            plugin = ide / "plugins/com.aspix2k.affected/3.15.1"
            plugin.mkdir(parents=True)
            (plugin / "verification-verdict.txt").write_text(
                "Compatible\n", encoding="utf-8"
            )
            (plugin / "dependencies.txt").write_text(
                "+--- (failed) com.intellij.gradle (optional): Unavailable\n"
                "+--- (failed) org.jetbrains.idea.maven (optional): Unavailable\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                plugin_verifier_reports.PluginVerifierReportError, "Gradle"
            ):
                plugin_verifier_reports.validate_report(
                    reports, "RD", "253.33813.59", "present", "unavailable"
                )

    def test_report_requires_the_declared_maven_descriptor_state(self) -> None:
        """Reject a report whose direct Maven descriptor state differs from support."""
        with TemporaryDirectory() as directory:
            reports = Path(directory)
            ide = reports / "RD-253.33813.59"
            plugin = ide / "plugins/com.aspix2k.affected/3.15.1"
            plugin.mkdir(parents=True)
            (plugin / "verification-verdict.txt").write_text(
                "Compatible\n", encoding="utf-8"
            )
            (plugin / "dependencies.txt").write_text(
                "+--- com.intellij.gradle:253.33813.59\n"
                "+--- org.jetbrains.idea.maven:253.33813.59\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                plugin_verifier_reports.PluginVerifierReportError, "Maven"
            ):
                plugin_verifier_reports.validate_report(
                    reports, "RD", "253.33813.59", "present", "unavailable"
                )


if __name__ == "__main__":
    unittest.main()
