"""Tests for the executable support matrix."""

from __future__ import annotations

import io
import json
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
from pathlib import Path
from tempfile import TemporaryDirectory

from scripts import ci_scope
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

    def test_selection_proof_allows_fail_closed_exact_scope(self) -> None:
        """ci_scope may skip native proof for unrelated changes without weakening it."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            workflow_path = root / ".github/workflows/conformance.yml"
            workflow = workflow_path.read_text(encoding="utf-8")
            self.write(
                workflow_path,
                workflow.replace(
                    "  verify:\n",
                    "  verify:\n    if: needs.scope.outputs.exact == 'true'\n",
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

    def test_platform_product_requires_product_specific_verifier_endpoints(self) -> None:
        """Reject a platform claim without its own minimum and current IDE cells."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            matrix = json.loads((root / "config/support-matrix.json").read_text())
            matrix["products"][0]["support"] = "platform"

            with self.assertRaisesRegex(
                support_matrix.SupportMatrixError, "verifier endpoints"
            ):
                support_matrix.validated(root, matrix)

    def test_platform_product_verifier_type_is_bound_to_the_claimed_product(self) -> None:
        """Reject an IntelliJ IDEA or sibling result substituted for the product."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            matrix = json.loads((root / "config/support-matrix.json").read_text())
            product = matrix["products"][0]
            product.update(
                {
                    "id": "rider",
                    "name": "Rider",
                    "support": "platform",
                    "verifier": {
                        "type": "GoLand",
                        "endpoints": [
                            {
                                "id": "minimum",
                                "version": "2025.3.5",
                                "build": "253.33813.59",
                                "gradle": "present",
                                "maven": "unavailable",
                            },
                            {
                                "id": "current",
                                "version": "2026.2.0.2",
                                "build": "262.8665.400",
                                "gradle": "present",
                                "maven": "unavailable",
                            },
                        ],
                    },
                }
            )

            with self.assertRaisesRegex(
                support_matrix.SupportMatrixError, "verifier product type"
            ):
                support_matrix.validated(root, matrix)

    def test_platform_product_requires_exactly_minimum_and_current_endpoints(self) -> None:
        """Reject a platform claim that omits either governed release boundary."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            matrix = json.loads((root / "config/support-matrix.json").read_text())
            product = matrix["products"][0]
            product.update(
                {
                    "id": "rider",
                    "name": "Rider",
                    "support": "platform",
                    "verifier": {
                        "type": "Rider",
                        "endpoints": [
                            {
                                "id": "minimum",
                                "version": "2025.3.5",
                                "build": "253.33813.59",
                                "gradle": "present",
                                "maven": "unavailable",
                            }
                        ],
                    },
                }
            )

            with self.assertRaisesRegex(
                support_matrix.SupportMatrixError, "minimum and current"
            ):
                support_matrix.validated(root, matrix)

    def test_product_verifier_rejects_unknown_optional_descriptor_state(self) -> None:
        """Keep Gradle and Maven descriptor expectations closed and executable."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            matrix = json.loads((root / "config/support-matrix.json").read_text())
            product = matrix["products"][0]
            product.update(
                {
                    "id": "rider",
                    "name": "Rider",
                    "support": "platform",
                    "verifier": {
                        "type": "Rider",
                        "endpoints": [
                            {
                                "id": "minimum",
                                "version": "2025.3.5",
                                "build": "253.33813.59",
                                "gradle": "sometimes",
                                "maven": "unavailable",
                            },
                            {
                                "id": "current",
                                "version": "2026.2.0.2",
                                "build": "262.8665.400",
                                "gradle": "present",
                                "maven": "unavailable",
                            },
                        ],
                    },
                }
            )

            with self.assertRaisesRegex(
                support_matrix.SupportMatrixError, "descriptor state"
            ):
                support_matrix.validated(root, matrix)

    def test_product_verifier_minimum_must_match_the_claimed_platform_line(self) -> None:
        """Reject a minimum verifier cell outside the advertised support line."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            matrix = json.loads((root / "config/support-matrix.json").read_text())
            product = matrix["products"][0]
            product.update(
                {
                    "id": "rider",
                    "name": "Rider",
                    "support": "platform",
                    "verifier": {
                        "type": "Rider",
                        "endpoints": [
                            {
                                "id": "minimum",
                                "version": "2025.2.9",
                                "build": "252.1.9",
                                "gradle": "present",
                                "maven": "unavailable",
                            },
                            {
                                "id": "current",
                                "version": "2026.2.0.2",
                                "build": "262.8665.400",
                                "gradle": "present",
                                "maven": "unavailable",
                            },
                        ],
                    },
                }
            )

            with self.assertRaisesRegex(
                support_matrix.SupportMatrixError, "minimum verifier version"
            ):
                support_matrix.validated(root, matrix)

    def test_product_verifier_current_must_follow_its_minimum(self) -> None:
        """Reject a current endpoint that is older than the supported minimum."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            matrix = json.loads((root / "config/support-matrix.json").read_text())
            product = matrix["products"][0]
            product.update(
                {
                    "id": "rider",
                    "name": "Rider",
                    "support": "platform",
                    "since": "2025.3",
                    "verifier": {
                        "type": "Rider",
                        "endpoints": [
                            {
                                "id": "minimum",
                                "version": "2025.3.5",
                                "build": "253.33813.59",
                                "gradle": "present",
                                "maven": "unavailable",
                            },
                            {
                                "id": "current",
                                "version": "2025.3.4",
                                "build": "253.33813.58",
                                "gradle": "present",
                                "maven": "unavailable",
                            },
                        ],
                    },
                }
            )

            with self.assertRaisesRegex(
                support_matrix.SupportMatrixError, "current verifier version"
            ):
                support_matrix.validated(root, matrix)

    def test_product_verifier_matrix_expands_each_governed_endpoint(self) -> None:
        """Render one deterministic CI cell for every product release boundary."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            matrix = json.loads((root / "config/support-matrix.json").read_text())
            product = matrix["products"][0]
            product.update(
                {
                    "id": "rider",
                    "name": "Rider",
                    "support": "platform",
                    "verifier": {
                        "type": "Rider",
                        "endpoints": [
                            {
                                "id": "minimum",
                                "version": "2025.3.5",
                                "build": "253.33813.59",
                                "gradle": "present",
                                "maven": "unavailable",
                            },
                            {
                                "id": "current",
                                "version": "2026.2.0.2",
                                "build": "262.8665.400",
                                "gradle": "present",
                                "maven": "unavailable",
                            },
                        ],
                    },
                }
            )
            validated = support_matrix.validated(root, matrix)
            builder = getattr(support_matrix, "verifier_matrix", None)

            self.assertIsNotNone(builder)
            if builder is not None:
                self.assertEqual(
                    [
                        {
                            "product": "rider",
                            "type": "Rider",
                            "code": "RD",
                            "endpoint": "minimum",
                            "version": "2025.3.5",
                            "build": "253.33813.59",
                            "gradle": "present",
                            "maven": "unavailable",
                        },
                        {
                            "product": "rider",
                            "type": "Rider",
                            "code": "RD",
                            "endpoint": "current",
                            "version": "2026.2.0.2",
                            "build": "262.8665.400",
                            "gradle": "present",
                            "maven": "unavailable",
                        },
                    ],
                    builder(validated),
                )

    def test_repository_product_verifier_matrix_has_all_governed_cells(self) -> None:
        """Pin the complete production matrix and its exceptional descriptor state."""
        root = Path(__file__).resolve().parents[2]
        matrix = support_matrix.validated(
            root,
            json.loads(
                (root / "config/support-matrix.json").read_text(encoding="utf-8")
            ),
        )
        cells = support_matrix.verifier_matrix(matrix)

        self.assertEqual(18, len(cells))
        self.assertEqual(9, len({cell["product"] for cell in cells}))
        self.assertTrue(all(cell["maven"] == "unavailable" for cell in cells))
        self.assertEqual(
            ["unavailable"],
            [
                cell["gradle"]
                for cell in cells
                if cell["product"] == "dataspell" and cell["endpoint"] == "current"
            ],
        )

    def test_product_verifier_matrix_cli_emits_bounded_json(self) -> None:
        """Expose the validated cells without teaching the workflow the schema."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_repository(root)
            matrix = json.loads((root / "config/support-matrix.json").read_text())
            matrix["products"][0].update(
                {
                    "id": "rider",
                    "name": "Rider",
                    "support": "platform",
                    "verifier": {
                        "type": "Rider",
                        "endpoints": [
                            {
                                "id": "minimum",
                                "version": "2025.3.5",
                                "build": "253.33813.59",
                                "gradle": "present",
                                "maven": "unavailable",
                            },
                            {
                                "id": "current",
                                "version": "2026.2.0.2",
                                "build": "262.8665.400",
                                "gradle": "present",
                                "maven": "unavailable",
                            },
                        ],
                    },
                }
            )
            self.write(root / "config/support-matrix.json", json.dumps(matrix))
            output = io.StringIO()

            try:
                with redirect_stdout(output):
                    result = support_matrix.main(
                        ["--verifier-matrix", "--root", str(root)]
                    )
            except SystemExit as error:
                self.fail(f"verifier matrix CLI is unavailable: {error}")

            self.assertEqual(0, result)
            cells = json.loads(output.getvalue())
            self.assertEqual(2, len(cells))
            self.assertEqual("RD", cells[0]["code"])

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
        root = Path(__file__).resolve().parents[2]
        support_matrix.check(root)
        support = (root / "docs/SUPPORT.md").read_text(encoding="utf-8")
        self.assertIn("static product-specific Plugin Verifier", support)
        self.assertIn("does not claim the installed IDE lifecycle", support)

    def test_product_verifier_uses_matrix_selected_type_archive_and_failure_levels(
        self,
    ) -> None:
        """Bind every matrix cell to one archive and fail on compatibility problems."""
        root = Path(__file__).resolve().parents[2]
        gradle = (root / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('gradleProperty("affected.verifier.type")', gradle)
        self.assertIn('gradleProperty("affected.verifier.version")', gradle)
        self.assertIn('gradleProperty("affected.verifier.archive")', gradle)
        self.assertIn("IntelliJPlatformType.valueOf(verifierType.get())", gradle)
        self.assertIn("archiveFile.set", gradle)
        self.assertIn("FailureLevel.COMPATIBILITY_PROBLEMS", gradle)
        self.assertNotIn('create(IntelliJPlatformType.Rider, "2025.3.5")', gradle)
        self.assertNotIn('create(IntelliJPlatformType.GoLand, "2025.3.5.1")', gradle)
        matrix = json.loads(
            (root / "config/support-matrix.json").read_text(encoding="utf-8")
        )
        products = {product["id"]: product for product in matrix["products"]}
        self.assertEqual("Rider", products["rider"]["verifier"]["type"])
        self.assertEqual("GoLand", products["goland"]["verifier"]["type"])
        self.assertEqual("CLion", products["clion"]["verifier"]["type"])
        self.assertEqual("planned", products["datagrip"]["support"])

    def test_conformance_workflow_runs_for_support_claim_changes(self) -> None:
        """Keep support claims inside the pull-request conformance trigger."""
        for path in (
            "config/support-matrix.json",
            "scripts/support_matrix.py",
            "scripts/tests/test_support_matrix.py",
            "docs/SUPPORT.md",
            "src/main/resources/META-INF/plugin.xml",
        ):
            with self.subTest(path=path):
                self.assertTrue(ci_scope.scope_for([path])["exact"])

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
