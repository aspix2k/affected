"""Tests for the executable support matrix."""

from __future__ import annotations

import json
import unittest
from datetime import datetime, timedelta, timezone
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

            with self.assertRaisesRegex(support_matrix.SupportMatrixError, "workflow"):
                support_matrix.check(root)

    def test_gate_must_be_a_workflow_with_an_executable_step(self) -> None:
        """Reject ordinary files and inert workflow declarations as CI gates."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            matrix = json.loads((root / "config/support-matrix.json").read_text())
            matrix["adapters"][0]["gates"] = ["README.md"]
            self.write(root / "config/support-matrix.json", json.dumps(matrix))

            with self.assertRaisesRegex(support_matrix.SupportMatrixError, "workflow"):
                support_matrix.check(root)

            matrix["adapters"][0]["gates"] = [".github/workflows/empty.yml"]
            self.write(root / "config/support-matrix.json", json.dumps(matrix))
            self.write(
                root / ".github/workflows/empty.yml",
                "name: empty\non: workflow_dispatch\njobs:\n  verify:\n    runs-on: ubuntu-latest\n    steps:\n      - name: inert\n",
            )

            with self.assertRaisesRegex(
                support_matrix.SupportMatrixError, "executable"
            ):
                support_matrix.check(root)

    def test_selection_proof_is_bound_to_its_declared_gate_execution(self) -> None:
        """Require the exact workflow command that executes every named proof."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            matrix = json.loads((root / "config/support-matrix.json").read_text())
            proof = matrix["adapters"][0]["selectionProofs"][0]
            proof["gateMarker"] = "absent command"
            self.write(root / "config/support-matrix.json", json.dumps(matrix))

            with self.assertRaisesRegex(
                support_matrix.SupportMatrixError, "gate marker"
            ):
                support_matrix.check(root)

    def test_selection_proof_marker_must_run_in_its_named_job_and_step(self) -> None:
        """Reject markers that occur only outside the claimed executable step."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            workflow_path = root / ".github/workflows/conformance.yml"
            workflow = workflow_path.read_text(encoding="utf-8")
            marker = "python -m unittest scripts.tests.test_support_matrix"
            cases = (
                workflow.replace(
                    "        run: python -m unittest scripts.tests.test_support_matrix",
                    "        run: echo verified",
                )
                + (
                    "  marker-only-name:\n"
                    "    runs-on: ubuntu-latest\n"
                    "    steps:\n"
                    f"      - name: {marker}\n"
                    "        run: echo verified\n"
                ),
                workflow.replace(
                    "        run: python -m unittest scripts.tests.test_support_matrix",
                    "        run: |\n          # python -m unittest scripts.tests.test_support_matrix\n          echo verified",
                ),
                workflow.replace(
                    "        run: python -m unittest scripts.tests.test_support_matrix",
                    "        run: echo verified",
                )
                + (
                    "  wrong-job:\n"
                    "    runs-on: ubuntu-latest\n"
                    "    steps:\n"
                    "      - name: Run support matrix tests\n"
                    "        run: python -m unittest scripts.tests.test_support_matrix\n"
                ),
            )
            for altered in cases:
                self.write(workflow_path, altered)
                with self.assertRaisesRegex(
                    support_matrix.SupportMatrixError, "gate marker"
                ):
                    support_matrix.check(root)

    def test_selection_proof_rejects_disabled_target_job_or_step(self) -> None:
        """Reject literal false conditions on the claimed workflow execution."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            workflow_path = root / ".github/workflows/conformance.yml"
            workflow = workflow_path.read_text(encoding="utf-8")
            cases = (
                workflow.replace("  verify:\n", "  verify:\n    if: false\n", 1)
                + (
                    "  enabled-job:\n"
                    "    runs-on: ubuntu-latest\n"
                    "    steps:\n"
                    "      - run: echo verified\n"
                ),
                workflow.replace(
                    "      - name: Run support matrix tests\n",
                    "      - name: Run support matrix tests\n"
                    "        if: $"
                    "{{ false }}\n",
                    1,
                )
                + "      - run: echo verified\n",
            )
            for altered in cases:
                self.write(workflow_path, altered)
                with self.assertRaisesRegex(
                    support_matrix.SupportMatrixError, "disabled"
                ):
                    support_matrix.check(root)

    def test_selection_proof_allows_fail_closed_plugin_scope(self) -> None:
        """ci_scope may skip plugin on docs; any other condition stays rejected."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            workflow_path = root / ".github/workflows/conformance.yml"
            workflow = workflow_path.read_text(encoding="utf-8")
            self.write(
                workflow_path,
                workflow.replace(
                    "  verify:\n",
                    "  verify:\n    if: needs.scope.outputs.plugin == 'true'\n",
                    1,
                ),
            )
            support_matrix.check(root)

    def test_selection_proof_rejects_conditional_target_job_or_step(self) -> None:
        """Reject proof executions whose conditions cannot be proven unconditional."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            workflow_path = root / ".github/workflows/conformance.yml"
            workflow = workflow_path.read_text(encoding="utf-8")
            cases = (
                workflow.replace(
                    "  verify:\n",
                    "  verify:\n    if: github.event_name == 'workflow_dispatch'\n",
                    1,
                ),
                workflow.replace(
                    "      - name: Run support matrix tests\n",
                    "      - name: Run support matrix tests\n"
                    "        if: github.event_name == 'workflow_dispatch'\n",
                    1,
                ),
            )
            for altered in cases:
                self.write(workflow_path, altered)
                with self.assertRaisesRegex(
                    support_matrix.SupportMatrixError, "conditional"
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

    def test_reviewed_date_must_be_calendar_valid_and_not_future(self) -> None:
        """Reject impossible and future exclusion-review dates."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            matrix = json.loads((root / "config/support-matrix.json").read_text())
            product = {
                "id": "retired",
                "name": "Retired IDE",
                "support": "excluded",
                "reason": "The product is no longer relevant to the supported platform range.",
                "reviewed": "2026-02-30",
            }
            matrix["products"].append(product)
            self.write(root / "config/support-matrix.json", json.dumps(matrix))

            with self.assertRaisesRegex(support_matrix.SupportMatrixError, "calendar"):
                support_matrix.check(root)

            product["reviewed"] = (
                datetime.now(timezone.utc).date() + timedelta(days=1)
            ).isoformat()
            self.write(root / "config/support-matrix.json", json.dumps(matrix))

            with self.assertRaisesRegex(support_matrix.SupportMatrixError, "Future"):
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

            support = (root / "docs/SUPPORT.md").read_text()
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
            self.write(root / "docs/SUPPORT.md", "stale\n")

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

    def test_support_entries_reject_unknown_fields(self) -> None:
        """Keep every support level closed to unreviewed schema extensions."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            matrix = json.loads((root / "config/support-matrix.json").read_text())
            matrix["products"] += [
                {
                    "id": "planned-ide",
                    "name": "Planned IDE",
                    "support": "planned",
                    "reason": "A dedicated adapter and public fixture are still required.",
                    "issue": "https://github.com/aspix2k/affected/issues/120",
                    "reviewed": "2026-08-12",
                },
                {
                    "id": "excluded-ide",
                    "name": "Excluded IDE",
                    "support": "excluded",
                    "reason": "The product is no longer relevant to the supported platform range.",
                    "reviewed": "2026-08-12",
                },
            ]
            matrix["operatingSystems"].append(
                {
                    "id": "macos",
                    "name": "macOS",
                    "support": "contract",
                    "fixtures": ["fixtures/os"],
                    "gates": [".github/workflows/conformance.yml"],
                }
            )
            cases = (
                *((product, "product") for product in matrix["products"]),
                *(
                    (operating_system, "operating system")
                    for operating_system in matrix["operatingSystems"]
                ),
                (matrix["adapters"][0], "adapter"),
                (matrix["adapters"][0]["selectionProofs"][0], "selection proof"),
            )
            for entry, label in cases:
                entry["unreviewed"] = True
                self.write(root / "config/support-matrix.json", json.dumps(matrix))
                with self.assertRaisesRegex(support_matrix.SupportMatrixError, label):
                    support_matrix.check(root)
                del entry["unreviewed"]

    def test_composite_selection_proof_must_cover_exactly_the_claimed_units(
        self,
    ) -> None:
        """Prevent one proof from silently supporting extra selection precision."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            matrix = json.loads((root / "config/support-matrix.json").read_text())
            matrix["adapters"][0]["selection"] = ["file", "package"]
            matrix["adapters"][0]["selectionProofs"][0]["units"] = ["file"]
            self.write(root / "config/support-matrix.json", json.dumps(matrix))

            with self.assertRaisesRegex(
                support_matrix.SupportMatrixError, "selection proofs"
            ):
                support_matrix.check(root)

    def test_repository_matrix_is_complete_and_current(self) -> None:
        """Validate the real production registrations and generated support page."""
        support_matrix.check(Path(__file__).resolve().parents[2])

    def test_conformance_workflow_runs_for_support_claim_changes(self) -> None:
        """Keep support claims inside the pull-request conformance trigger."""
        workflow = (
            Path(__file__).resolve().parents[2] / ".github/workflows/conformance.yml"
        ).read_text(encoding="utf-8")
        for path in (
            "config/support-matrix.json",
            "scripts/support_matrix.py",
            "scripts/tests/test_support_matrix.py",
            "docs/SUPPORT.md",
            "src/main/resources/META-INF/plugin.xml",
        ):
            self.assertIn(f'- "{path}"', workflow)

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
            "selectionProofs": [
                {
                    "units": ["package"],
                    "path": "fixtures/selection-test.txt",
                    "marker": "selects one package",
                    "gate": ".github/workflows/conformance.yml",
                    "gateJob": "verify",
                    "gateStep": "Run support matrix tests",
                    "gateMarker": "python -m unittest scripts.tests.test_support_matrix",
                }
            ],
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
        workflow = (
            "name: conformance\n"
            "on: workflow_dispatch\n"
            "jobs:\n"
            "  verify:\n"
            "    runs-on: ubuntu-latest\n"
            "    steps:\n"
            "      - name: Run support matrix tests\n"
            "        run: python -m unittest scripts.tests.test_support_matrix\n"
        )
        self.write(root / ".github/workflows/conformance.yml", workflow)
        self.write(root / ".github/workflows/ci.yml", workflow)
        self.write(
            root / "README.md",
            f"{support_matrix.SUMMARY_START}\n{support_matrix.readme_summary(matrix)}\n"
            f"{support_matrix.SUMMARY_END}\n",
        )
        self.write(root / "docs/SUPPORT.md", support_matrix.render(matrix))

    @staticmethod
    def write(path: Path, content: str) -> None:
        """Write a UTF-8 fixture after creating its parent directory."""
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
