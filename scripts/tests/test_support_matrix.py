"""Tests for the executable support matrix."""

from __future__ import annotations

import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from scripts import support_matrix


class SupportMatrixTest(unittest.TestCase):
    """Exercise matrix completeness, evidence, and generated documentation."""

    def test_registered_adapter_must_be_documented(self) -> None:
        """Reject a production extension that has no matrix entry."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            self.write(
                root / "src/main/resources/META-INF/plugin.xml",
                '<idea-plugin><extensions defaultExtensionNs="com.aspix2k.affected">'
                '<buildSystem implementation="example.MissingBuildSystem"/>'
                "</extensions></idea-plugin>",
            )

            with self.assertRaisesRegex(
                support_matrix.SupportMatrixError, "MissingBuildSystem"
            ):
                support_matrix.check(root)

    def test_supported_entry_requires_existing_fixture_and_gate(self) -> None:
        """Reject support claims without executable repository evidence."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            matrix = json.loads((root / "config/support-matrix.json").read_text())
            matrix["adapters"][0]["fixtures"] = ["missing-fixture"]
            self.write(root / "config/support-matrix.json", json.dumps(matrix))

            with self.assertRaisesRegex(
                support_matrix.SupportMatrixError, "missing-fixture"
            ):
                support_matrix.check(root)

    def test_gate_evidence_must_be_an_executable_file_boundary(self) -> None:
        """Reject a directory passed off as an executable CI gate."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            matrix = json.loads((root / "config/support-matrix.json").read_text())
            matrix["adapters"][0]["gates"] = [".github/workflows"]
            self.write(root / "config/support-matrix.json", json.dumps(matrix))

            with self.assertRaisesRegex(
                support_matrix.SupportMatrixError, "regular file"
            ):
                support_matrix.check(root)

    def test_excluded_product_requires_a_dated_reason(self) -> None:
        """Keep exclusions explicit, dated, and reviewable."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            matrix = json.loads((root / "config/support-matrix.json").read_text())
            matrix["products"].append(
                {
                    "id": "retired",
                    "name": "Retired IDE",
                    "support": "excluded",
                    "reason": "No longer relevant.",
                    "reviewed": "today",
                }
            )
            self.write(root / "config/support-matrix.json", json.dumps(matrix))

            with self.assertRaisesRegex(support_matrix.SupportMatrixError, "reviewed"):
                support_matrix.check(root)

    def test_planned_product_is_linked_and_not_counted_as_supported(self) -> None:
        """Publish future coverage without presenting it as available today."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            matrix = json.loads((root / "config/support-matrix.json").read_text())
            matrix["products"].append(
                {
                    "id": "planned-ide",
                    "name": "Planned IDE",
                    "support": "planned",
                    "reason": "A dedicated adapter and public fixture are still required.",
                    "issue": "https://github.com/aspix2k/affected/issues/120",
                    "reviewed": "2026-08-12",
                }
            )
            self.write(root / "config/support-matrix.json", json.dumps(matrix))

            support_matrix.write(root)

            support = (root / "SUPPORT.md").read_text()
            readme = (root / "README.md").read_text()
            self.assertIn("## Planned coverage", support)
            self.assertIn("[Issue #120]", support)
            self.assertIn("1 JetBrains products", readme)
            support_matrix.check(root)

    def test_generated_support_page_must_match_the_matrix(self) -> None:
        """Detect hand-edited or stale human-facing support claims."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            self.write(root / "SUPPORT.md", "stale\n")

            with self.assertRaisesRegex(
                support_matrix.SupportMatrixError, "SUPPORT.md"
            ):
                support_matrix.check(root)

    def test_selection_unit_must_be_a_closed_machine_value(self) -> None:
        """Reject new precision claims until the validator understands their semantics."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            matrix = json.loads((root / "config/support-matrix.json").read_text())
            matrix["adapters"][0]["selection"] = ["magic"]
            self.write(root / "config/support-matrix.json", json.dumps(matrix))

            with self.assertRaisesRegex(support_matrix.SupportMatrixError, "magic"):
                support_matrix.check(root)

    def test_known_selection_unit_requires_a_specific_regression_proof(self) -> None:
        """Reject a plausible precision claim that is not backed by its named test."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            matrix = json.loads((root / "config/support-matrix.json").read_text())
            matrix["adapters"][0]["selection"] = ["test"]
            self.write(root / "config/support-matrix.json", json.dumps(matrix))

            with self.assertRaisesRegex(
                support_matrix.SupportMatrixError, "selection proofs"
            ):
                support_matrix.check(root)

    def test_repository_matrix_is_complete_and_current(self) -> None:
        """Validate the real production registrations and generated support page."""
        support_matrix.check(Path(__file__).resolve().parents[2])

    def write_repository(
        self, root: Path, adapters: list[dict[str, object]] | None = None
    ) -> None:
        """Create a minimal repository accepted by the matrix validator."""
        adapter = {
            "id": "EXAMPLE",
            "implementation": "example.ExampleBuildSystem",
            "ecosystem": "Example",
            "languages": ["Example"],
            "runners": ["Example runner"],
            "selection": ["package"],
            "selectionProofs": {
                "package": {
                    "path": "fixtures/selection-test.txt",
                    "marker": "selects one package",
                }
            },
            "versions": "1.0",
            "support": "supported",
            "fixtures": ["fixtures/example"],
            "gates": [".github/workflows/conformance.yml"],
        }
        matrix = {
            "schema": 1,
            "products": [
                {
                    "id": "example-ide",
                    "name": "Example IDE",
                    "support": "verified",
                    "since": "2025.3",
                    "fixtures": ["fixtures/ide"],
                    "gates": [".github/workflows/ci.yml"],
                }
            ],
            "operatingSystems": [
                {
                    "id": "linux",
                    "name": "Linux",
                    "support": "native",
                    "fixtures": ["fixtures/os"],
                    "gates": [".github/workflows/conformance.yml"],
                }
            ],
            "adapters": adapters if adapters is not None else [adapter],
        }
        self.write(root / "config/support-matrix.json", json.dumps(matrix))
        self.write(
            root / "src/main/resources/META-INF/plugin.xml",
            "<idea-plugin><description><![CDATA[\n"
            f"{support_matrix.SUMMARY_START}\n{support_matrix.plugin_summary(matrix)}\n"
            f"{support_matrix.SUMMARY_END}\n]]></description>"
            '<extensions defaultExtensionNs="com.aspix2k.affected">'
            '<buildSystem implementation="example.ExampleBuildSystem"/>'
            "</extensions></idea-plugin>",
        )
        self.write(root / "fixtures/example", "fixture\n")
        self.write(root / "fixtures/ide", "fixture\n")
        self.write(root / "fixtures/os", "fixture\n")
        self.write(root / "fixtures/selection-test.txt", "selects one package\n")
        self.write(root / ".github/workflows/conformance.yml", "name: conformance\n")
        self.write(root / ".github/workflows/ci.yml", "name: ci\n")
        self.write(
            root / "README.md",
            f"{support_matrix.SUMMARY_START}\n{support_matrix.readme_summary(matrix)}\n"
            f"{support_matrix.SUMMARY_END}\n",
        )
        self.write(root / "SUPPORT.md", support_matrix.render(matrix))

    @staticmethod
    def write(path: Path, content: str) -> None:
        """Write a UTF-8 fixture after creating its parent directory."""
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
