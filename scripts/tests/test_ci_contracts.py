"""Regression tests for the fail-closed pull-request CI shape."""

from __future__ import annotations

import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from scripts import ci_contracts


class CiContractsTest(unittest.TestCase):
    """Reject a split Gradle graph, a fake CLI matrix and a missing aggregator."""

    def test_current_repository_must_satisfy_the_contract(self) -> None:
        """Validate the production workflows."""
        ci_contracts.check(Path(__file__).resolve().parents[2])

    def test_three_gradle_invocations_are_rejected(self) -> None:
        """Keep plugin analysis, tests and verification on one Gradle graph."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            ci = root / ".github/workflows/ci.yml"
            ci.write_text(
                ci.read_text(encoding="utf-8").replace(
                    "scripts/run_gradle.sh --no-daemon --max-workers=2",
                    "scripts/run_gradle.sh :detekt\n          ./gradlew --no-daemon --max-workers=2",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "exactly once"):
                ci_contracts.check(root)

    def test_wrapper_must_not_use_a_single_ten_second_fetch(self) -> None:
        """A 10s timeout with retries=0 is how CI died on services.gradle.org."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            wrapper = root / "gradle/wrapper/gradle-wrapper.properties"
            wrapper.write_text(
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.0-bin.zip\n"
                "distributionSha256Sum=abc\n"
                "networkTimeout=10000\n"
                "retries=0\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "networkTimeout"):
                ci_contracts.check(root)

    def test_readme_must_not_start_conformance(self) -> None:
        """Documentation-only README edits are not exact-impact evidence."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/conformance.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "  pull_request:\n",
                    "  pull_request:\n    paths:\n      - \"README.md\"\n",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "README"):
                ci_contracts.check(root)

    def test_required_checks_must_run_on_merge_group(self) -> None:
        """A merge queue without merge_group waits forever for verify."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/ci.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace("  merge_group:\n", "", 1),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "merge_group"):
                ci_contracts.check(root)

    def test_exact_impact_must_run_on_merge_group(self) -> None:
        """The native aggregate must report on the exact merge-queue commit."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/conformance.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace("  merge_group:\n", "", 1),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                ci_contracts.CiContractError,
                "conformance.yml must trigger on merge_group",
            ):
                ci_contracts.check(root)

    def test_exact_impact_aggregate_must_keep_every_lane(self) -> None:
        """A green aggregate cannot omit the slow native fixture lane."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/conformance.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "needs: [scope, exact-impact, cross-platform-paths, cli-native, "
                    "dotnet-sdks, phpunit-versions]",
                    "needs: [scope, exact-impact, cross-platform-paths, dotnet-sdks, "
                    "phpunit-versions]",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                ci_contracts.CiContractError, "every exact-impact lane"
            ):
                ci_contracts.check(root)

    def test_exact_impact_aggregate_must_fail_closed_on_cancellation(self) -> None:
        """Cancelling a current-head lane cannot turn its required check into skipped success."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/conformance.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "if: ${{ always() }}",
                    "if: ${{ always() && !cancelled() }}",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "always"):
                ci_contracts.check(root)

    def test_exact_impact_aggregate_must_fail_closed_on_unknown_scope(self) -> None:
        """Only an explicit false scope may accept a skipped exact-impact lane."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/conformance.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    'if [ "$required" = false ]; then',
                    'if [ "${required:-true}" = true ]; then',
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "required"):
                ci_contracts.check(root)

    def test_exact_impact_scope_must_publish_the_classifier_result(self) -> None:
        """A constant false scope must not skip every exact-impact lane."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/conformance.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "exact: ${{ steps.classify.outputs.exact }}",
                    "exact: false",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "classifier"):
                ci_contracts.check(root)

    def test_exact_impact_aggregate_must_check_the_cli_result(self) -> None:
        """A failed native CLI lane must reach the required aggregate."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/conformance.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    '          require_when cli-native "${EXACT_REQUIRED:-true}" "$CLI_RESULT"\n',
                    "",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "cli-native"):
                ci_contracts.check(root)

    def test_exact_impact_aggregate_must_not_miswire_the_cli_result(self) -> None:
        """The CLI lane must not accidentally check another lane twice."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/conformance.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    'require_when cli-native "${EXACT_REQUIRED:-true}" "$CLI_RESULT"',
                    'require_when cli-native "${EXACT_REQUIRED:-true}" "$PATHS_RESULT"',
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "cli-native"):
                ci_contracts.check(root)

    def test_native_python_adapter_lint_must_cover_every_bundled_adapter(self) -> None:
        """Both Ruff gates must scan the canonical adapter directory recursively."""
        desired = "core/src/main/python"
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            ci_contracts.check(root)

        cases = {
            "check": (
                f"python -m ruff check {desired}",
                "python -m ruff check core/src/main/python/affected_pytest.py",
            ),
            "format": (
                f"python -m ruff format --check {desired}",
                "python -m ruff format --check core/src/main/python/affected_unittest.py",
            ),
        }
        for name, (required, narrowed) in cases.items():
            with self.subTest(name=name), TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_workflows(root)
                path = root / ".github/workflows/conformance.yml"
                text = path.read_text(encoding="utf-8")
                path.write_text(text.replace(required, narrowed, 1), encoding="utf-8")

                with self.assertRaisesRegex(ci_contracts.CiContractError, "Python adapters"):
                    ci_contracts.check(root)

    def test_native_fixture_tools_must_skip_recommended_packages(self) -> None:
        """Optional R tooling must not consume the bounded native-fixture job."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/conformance.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "sudo apt-get install -y --no-install-recommends",
                    "sudo apt-get install -y",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "recommended packages"):
                ci_contracts.check(root)

    def test_cross_platform_gate_must_prove_process_containment(self) -> None:
        """Windows Job and POSIX session cleanup must remain in the OS matrix."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/conformance.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "          --tests com.aspix2k.affected.build.SequentialProcessCancellationTest\n",
                    "",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "containment"):
                ci_contracts.check(root)

    def test_cross_platform_gate_must_prove_windows_unittest_junction_rejection(self) -> None:
        """The required Windows lane must reject native unittest junctions."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/conformance.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "          --tests com.aspix2k.affected.build."
                    "CliUnittestWindowsJunctionConformanceTest\n",
                    "",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "Windows unittest junction proof"):
                ci_contracts.check(root)

    def test_windows_unittest_junction_proof_keeps_its_runtime_prerequisites(self) -> None:
        """The native proof must run with conformance enabled and governed Python."""
        cases = {
            "conformance": (
                "          -Paffected.cliConformance=true\n",
                "",
            ),
            "python": (
                "      - uses: actions/setup-python@"
                "5fda3b95a4ea91299a34e894583c3862153e4b97 # v7.0.0\n"
                "        if: runner.os == 'Windows'\n"
                "        with:\n"
                '          python-version: "3.14.7"\n',
                "",
            ),
        }
        for name, (required, weakened) in cases.items():
            with self.subTest(name=name), TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_workflows(root)
                path = root / ".github/workflows/conformance.yml"
                workflow = path.read_text(encoding="utf-8")
                self.assertIn(required, workflow)
                path.write_text(workflow.replace(required, weakened, 1), encoding="utf-8")

                with self.assertRaisesRegex(
                    ci_contracts.CiContractError, "Windows unittest junction proof"
                ):
                    ci_contracts.check(root)

    def test_windows_unittest_junction_proof_keeps_the_windows_matrix_leg(self) -> None:
        """The native proof must keep its Windows matrix leg and runner binding."""
        mutations = {
            "matrix": (
                "        os: [macos-latest, windows-latest]",
                "        os: [macos-latest]",
            ),
            "runner": (
                "    if: needs.scope.outputs.exact == 'true'\n"
                "    runs-on: ${{ matrix.os }}\n"
                "    timeout-minutes: 20",
                "    if: needs.scope.outputs.exact == 'true'\n"
                "    runs-on: macos-latest\n"
                "    timeout-minutes: 20",
            ),
        }
        for name, (required, weakened) in mutations.items():
            with self.subTest(name=name), TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_workflows(root)
                path = root / ".github/workflows/conformance.yml"
                path.write_text(
                    path.read_text(encoding="utf-8").replace(
                        required,
                        weakened,
                        1,
                    ),
                    encoding="utf-8",
                )

                with self.assertRaisesRegex(
                    ci_contracts.CiContractError, "Windows unittest junction proof"
                ):
                    ci_contracts.check(root)

    def test_windows_unittest_junction_proof_keeps_exact_scope_dataflow(self) -> None:
        """The required Windows job must depend on and follow the exact classifier."""
        mutations = {
            "needs": (
                "  cross-platform-paths:\n"
                "    name: Cross-platform paths / ${{ matrix.os }}\n"
                "    needs: [scope]\n",
                "  cross-platform-paths:\n"
                "    name: Cross-platform paths / ${{ matrix.os }}\n"
                "    env:\n"
                '      CONTRACT_DECOY: "needs: [scope]"\n',
            ),
            "if": (
                "  cross-platform-paths:\n"
                "    name: Cross-platform paths / ${{ matrix.os }}\n"
                "    needs: [scope]\n"
                "    if: needs.scope.outputs.exact == 'true'\n",
                "  cross-platform-paths:\n"
                "    name: Cross-platform paths / ${{ matrix.os }}\n"
                "    needs: [scope]\n"
                "    if: false\n"
                "    env:\n"
                "      CONTRACT_DECOY: \"needs.scope.outputs.exact == 'true'\"\n",
            ),
        }
        for name, (required, weakened) in mutations.items():
            with self.subTest(name=name), TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_workflows(root)
                path = root / ".github/workflows/conformance.yml"
                workflow = path.read_text(encoding="utf-8")
                self.assertIn(required, workflow)
                path.write_text(workflow.replace(required, weakened, 1), encoding="utf-8")

                with self.assertRaisesRegex(
                    ci_contracts.CiContractError, "Windows unittest junction proof"
                ):
                    ci_contracts.check(root)

    def test_windows_unittest_junction_proof_rejects_decoy_runtime_tokens(self) -> None:
        """Tokens outside their executable steps cannot satisfy the native proof."""
        mutations = {
            "conformance": (
                "          -Paffected.cliConformance=true\n",
                "",
                "          name: cross-platform-paths-${{ matrix.os }}\n",
                "          name: cross-platform-paths-${{ matrix.os }}-Paffected.cliConformance=true\n",
            ),
            "python-condition": (
                "        if: runner.os == 'Windows'\n",
                "        if: runner.os == 'macOS'\n",
                "          name: cross-platform-paths-${{ matrix.os }}\n",
                '          name: "if: runner.os == \'Windows\'"\n',
            ),
        }
        for name, (required, weakened, anchor, decoy) in mutations.items():
            with self.subTest(name=name), TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_workflows(root)
                path = root / ".github/workflows/conformance.yml"
                workflow = path.read_text(encoding="utf-8")
                self.assertIn(required, workflow)
                self.assertIn(anchor, workflow)
                workflow = workflow.replace(required, weakened, 1).replace(anchor, decoy, 1)
                path.write_text(workflow, encoding="utf-8")

                with self.assertRaisesRegex(
                    ci_contracts.CiContractError, "Windows unittest junction proof"
                ):
                    ci_contracts.check(root)

    def test_windows_unittest_junction_proof_rejects_intra_step_decoys(self) -> None:
        """Runtime prerequisites must stay in run, top-level if, and with mappings."""
        mutations = {
            "run": (
                "          -Paffected.cliConformance=true\n",
                "",
                "      - name: Run platform-specific core tests\n",
                "      - name: Run platform-specific core tests\n"
                '        env:\n          CONTRACT_DECOY: "-Paffected.cliConformance=true"\n',
            ),
            "run-after": (
                "          -Paffected.cliConformance=true\n",
                "",
                "          --tests com.aspix2k.affected.build.XcodeNativeTest\n"
                "          --rerun-tasks --no-daemon --no-parallel --max-workers=1\n",
                "          --tests com.aspix2k.affected.build.XcodeNativeTest\n"
                "          --rerun-tasks --no-daemon --no-parallel --max-workers=1\n"
                '        env:\n          CONTRACT_DECOY: "-Paffected.cliConformance=true"\n',
            ),
            "disabled-step": (
                "      - name: Run platform-specific core tests\n"
                "        shell: bash\n",
                "      - name: Run platform-specific core tests\n"
                "        if: false\n"
                "        shell: bash\n",
                "",
                "",
            ),
            "action-if": (
                "        if: runner.os == 'Windows'\n"
                "        with:\n",
                "        if: runner.os == 'macOS'\n"
                "        with:\n"
                "          if: runner.os == 'Windows'\n",
                "",
                "",
            ),
            "action-with": (
                "        with:\n"
                '          python-version: "3.14.7"\n',
                "        env:\n"
                '          python-version: "3.14.7"\n',
                "",
                "",
            ),
        }
        for name, (required, weakened, anchor, decoy) in mutations.items():
            with self.subTest(name=name), TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_workflows(root)
                path = root / ".github/workflows/conformance.yml"
                workflow = path.read_text(encoding="utf-8")
                self.assertIn(required, workflow)
                workflow = workflow.replace(required, weakened, 1)
                if anchor:
                    self.assertIn(anchor, workflow)
                    workflow = workflow.replace(anchor, decoy, 1)
                path.write_text(workflow, encoding="utf-8")

                with self.assertRaisesRegex(
                    ci_contracts.CiContractError, "Windows unittest junction proof"
                ):
                    ci_contracts.check(root)

    def test_windows_unittest_junction_proof_rejects_ignored_failures(self) -> None:
        """Neither the required job nor its executable step may ignore failures."""
        mutations = {
            "job": (
                "    if: needs.scope.outputs.exact == 'true'\n"
                "    runs-on: ${{ matrix.os }}\n"
                "    timeout-minutes: 20",
                "    if: needs.scope.outputs.exact == 'true'\n"
                "    runs-on: ${{ matrix.os }}\n"
                "    continue-on-error: true\n"
                "    timeout-minutes: 20",
            ),
            "step": (
                "      - name: Run platform-specific core tests\n",
                "      - name: Run platform-specific core tests\n"
                "        continue-on-error: true\n",
            ),
            "python-step": (
                "      - uses: actions/setup-python@"
                "5fda3b95a4ea91299a34e894583c3862153e4b97 # v7.0.0\n"
                "        if: runner.os == 'Windows'\n",
                "      - uses: actions/setup-python@"
                "5fda3b95a4ea91299a34e894583c3862153e4b97 # v7.0.0\n"
                "        continue-on-error: true\n"
                "        if: runner.os == 'Windows'\n",
            ),
        }
        for name, (required, weakened) in mutations.items():
            with self.subTest(name=name), TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_workflows(root)
                path = root / ".github/workflows/conformance.yml"
                workflow = path.read_text(encoding="utf-8")
                self.assertIn(required, workflow)
                path.write_text(workflow.replace(required, weakened, 1), encoding="utf-8")

                with self.assertRaisesRegex(
                    ci_contracts.CiContractError, "Windows unittest junction proof"
                ):
                    ci_contracts.check(root)

    def test_windows_unittest_junction_proof_executes_gradle_fail_closed(self) -> None:
        """The governed run must invoke Gradle through bash and preserve its exit status."""
        mutations = {
            "command": (
                "          scripts/run_gradle.sh :core:test\n",
                "          echo scripts/run_gradle.sh :core:test\n",
            ),
            "exit": (
                "          --tests com.aspix2k.affected.build.XcodeNativeTest\n"
                "          -Paffected.cliConformance=true\n"
                "          --rerun-tasks --no-daemon --no-parallel --max-workers=1\n",
                "          --tests com.aspix2k.affected.build.XcodeNativeTest\n"
                "          -Paffected.cliConformance=true\n"
                "          --rerun-tasks --no-daemon --no-parallel --max-workers=1 || true\n",
            ),
            "intermediate-operator": (
                "          --tests com.aspix2k.affected.build.CliUnittestWindowsJunctionConformanceTest\n",
                "          || true\n"
                "          --tests com.aspix2k.affected.build.CliUnittestWindowsJunctionConformanceTest\n",
            ),
            "shell": (
                "      - name: Run platform-specific core tests\n"
                "        shell: bash\n",
                "      - name: Run platform-specific core tests\n"
                "        shell: bash {0} || true\n",
            ),
        }
        for name, (required, weakened) in mutations.items():
            with self.subTest(name=name), TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_workflows(root)
                path = root / ".github/workflows/conformance.yml"
                workflow = path.read_text(encoding="utf-8")
                self.assertIn(required, workflow)
                path.write_text(workflow.replace(required, weakened, 1), encoding="utf-8")

                with self.assertRaisesRegex(
                    ci_contracts.CiContractError, "Windows unittest junction proof"
                ):
                    ci_contracts.check(root)

    def test_verify_aggregate_must_check_the_scripts_result(self) -> None:
        """A failed scripts lane must reach the required verify aggregate."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/ci.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    '          require_success scripts "$SCRIPT_RESULT"\n',
                    "",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "scripts"):
                ci_contracts.check(root)

    def test_verify_aggregate_must_always_report(self) -> None:
        """Cancelling a fast lane must not skip the required verify context."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/ci.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "  verify:\n    name: verify\n    if: ${{ always() }}",
                    "  verify:\n    name: verify\n    if: ${{ !cancelled() }}",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "always"):
                ci_contracts.check(root)

    def test_verify_aggregate_must_not_miswire_the_plugin_result(self) -> None:
        """The plugin lane must not accidentally check the health result."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/ci.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    'require_when plugin "${PLUGIN_REQUIRED:-true}" "$PLUGIN_RESULT"',
                    'require_when plugin "${PLUGIN_REQUIRED:-true}" "$HEALTH_RESULT"',
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "plugin"):
                ci_contracts.check(root)

    def test_required_aggregate_documentation_must_include_exact_impact(self) -> None:
        """The documented ruleset contexts must not drift from the workflow aggregate."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / "docs/CONTRIBUTING.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "required checks `verify` and `exact-impact`",
                    "required check `verify`",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "every required aggregate"):
                ci_contracts.check(root)

    def test_verify_required_context_name_must_remain_stable(self) -> None:
        """Renaming a required display name must fail before the ruleset waits forever."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/ci.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "    name: verify\n",
                    "    name: verify-renamed\n",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "verify context name"):
                ci_contracts.check(root)

    def test_exact_impact_required_context_name_must_remain_stable(self) -> None:
        """The native aggregate display name is the live ruleset context."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/conformance.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "    name: exact-impact\n",
                    "    name: exact-impact-renamed\n",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "exact-impact context name"):
                ci_contracts.check(root)

    def test_codeql_must_use_the_kotlin_compatibility_shim_twice(self) -> None:
        """Both CodeQL builds must keep the bounded Kotlin compiler compatibility seam."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/codeql.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "          --init-script scripts/codeql-kotlin-compat.init.gradle\n",
                    "",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "Kotlin compatibility"):
                ci_contracts.check(root)

    def test_codeql_kotlin_compatibility_forbids_the_build_cache(self) -> None:
        """The analysis-only older compiler must never deserialize a shared build cache."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/codeql.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "--no-build-cache",
                    "--build-cache",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "build cache"):
                ci_contracts.check(root)

    def test_codeql_kotlin_compatibility_rejects_property_drift(self) -> None:
        """The workflow cannot silently select an unreviewed analysis compiler."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/codeql.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "-Paffected.codeql.kotlinPluginVersion=2.4.10",
                    "-Paffected.codeql.kotlinPluginVersion=2.4.20-RC",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "Kotlin compatibility"):
                ci_contracts.check(root)

    def test_codeql_kotlin_compatibility_rejects_shim_drift(self) -> None:
        """Changing the bounded version map requires an explicit contract review."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / "scripts/codeql-kotlin-compat.init.gradle"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    'details.requested.version != "2.4.20-RC"',
                    'details.requested.version != "2.4.20"',
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "changed without review"):
                ci_contracts.check(root)

    def test_codeql_kotlin_compatibility_rejects_probe_drift(self) -> None:
        """The executed behavioral proof cannot silently become a no-op."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / "scripts/codeql_kotlin_compat_probe.py"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "require_success(project)",
                    "logging.info('skipped')",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "probe changed"):
                ci_contracts.check(root)

    def test_codeql_kotlin_compatibility_stays_out_of_dependency_graph(self) -> None:
        """Dependency resolution must never inherit the analysis-only compiler."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/dependency-graph.yml"
            path.write_text(
                path.read_text(encoding="utf-8")
                + "\n  # --init-script scripts/codeql-kotlin-compat.init.gradle\n"
                + "  # -Paffected.codeql.kotlinPluginVersion=2.4.10\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "analysis-only"):
                ci_contracts.check(root)

    def test_codeql_kotlin_compatibility_stays_out_of_shared_gradle_runner(self) -> None:
        """The shared runner must preserve the requested production compiler."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / "scripts/run_gradle.sh"
            path.write_text(
                path.read_text(encoding="utf-8")
                + "\n# --init-script scripts/codeql-kotlin-compat.init.gradle\n"
                + "# -Paffected.codeql.kotlinPluginVersion=2.4.10\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "analysis-only"):
                ci_contracts.check(root)

    def test_codeql_must_run_the_kotlin_compatibility_probe_before_init(self) -> None:
        """The real RC rewrite must be proven before CodeQL starts recording product code."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/codeql.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "      - name: Verify Kotlin compatibility shim\n"
                    "        if: steps.scope.outputs.codeql == 'true'\n"
                    "        run: python3 scripts/codeql_kotlin_compat_probe.py\n\n",
                    "",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "behavioral probe"):
                ci_contracts.check(root)

    def test_plugin_must_stay_scoped(self) -> None:
        """A required plugin job without a scope condition re-downloads IDEs for docs."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/ci.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "    if: needs.scope.outputs.plugin == 'true'\n",
                    "",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "plugin must run only"):
                ci_contracts.check(root)

    def test_product_verifier_matrix_is_a_required_plugin_gate(self) -> None:
        """A plugin change cannot merge without every product verifier cell."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/ci.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "  product-verifier:\n", "  product-verifier-removed:\n", 1
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                ci_contracts.CiContractError, "product verifier"
            ):
                ci_contracts.check(root)

    def test_product_verifier_matrix_keeps_provenance_and_bounded_execution(self) -> None:
        """Reject matrix drift that rebuilds, substitutes, or stops validating evidence."""
        cases = {
            "source": (
                "matrix=$(python3 scripts/support_matrix.py --verifier-matrix)",
                "matrix=[]",
            ),
            "matrix": (
                "include: ${{ fromJSON(needs.scope.outputs.verifier) }}",
                "include: []",
            ),
            "parallelism": ("max-parallel: 4", "max-parallel: 18"),
            "timeout": ("timeout-minutes: 30", "timeout-minutes: 60"),
            "download": (
                "actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c",
                "actions/download-artifact@main",
            ),
            "provenance": (
                "python3 scripts/plugin_verifier_reports.py artifact",
                "echo skipped-artifact-validation",
            ),
            "archive": (
                '"-Paffected.verifier.archive=${{ steps.artifact.outputs.archive }}"',
                '"-Paffected.verifier.archive=build/distributions/unreviewed.zip"',
            ),
            "product": (
                '"-Paffected.verifier.type=${{ matrix.type }}"',
                '"-Paffected.verifier.type=IntellijIdea"',
            ),
            "rebuild": ("verifyPlugin -x buildPlugin", "verifyPlugin"),
            "report": (
                "python3 scripts/plugin_verifier_reports.py report",
                "echo skipped-report-validation",
            ),
            "report-state": (
                '"--gradle=${{ matrix.gradle }}"',
                '"--gradle=present"',
            ),
            "report-build": (
                '"--build=${{ matrix.build }}"',
                '"--build=253.33813.59"',
            ),
            "failure-artifact": (
                "        if: always()\n        with:\n          name: product-verifier-",
                "        if: success()\n        with:\n          name: product-verifier-",
            ),
        }
        for name, (required, weakened) in cases.items():
            with self.subTest(name=name), TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_workflows(root)
                path = root / ".github/workflows/ci.yml"
                workflow = path.read_text(encoding="utf-8")
                self.assertIn(required, workflow)
                path.write_text(workflow.replace(required, weakened, 1), encoding="utf-8")

                with self.assertRaisesRegex(
                    ci_contracts.CiContractError, "product verifier"
                ):
                    ci_contracts.check(root)

    def test_verify_aggregate_checks_the_product_verifier_result(self) -> None:
        """A failed product cell must reach the required verify context."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/ci.yml"
            workflow = path.read_text(encoding="utf-8")
            path.write_text(
                workflow.replace(
                    'require_when product-verifier "${PLUGIN_REQUIRED:-true}" '
                    '"$PRODUCT_VERIFIER_RESULT"',
                    'require_when product-verifier "${PLUGIN_REQUIRED:-true}" '
                    '"$PLUGIN_RESULT"',
                    1,
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                ci_contracts.CiContractError, "product-verifier"
            ):
                ci_contracts.check(root)

    def test_queue_must_not_merge_immediately(self) -> None:
        """Agents enqueue. GitHub merges after required checks."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/queue.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(" --auto", "", 1),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "--auto"):
                ci_contracts.check(root)

    def test_dependabot_version_update_prs_are_rejected(self) -> None:
        """Keep dependency discovery read-only instead of opening bot pull requests."""
        for filename in ("dependabot.yml", "dependabot.yaml"):
            with self.subTest(filename=filename), TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_workflows(root)
                dependabot = root / ".github" / filename
                dependabot.write_text(
                    "version: 2\nupdates:\n  - package-ecosystem: gradle\n    directory: /\n",
                    encoding="utf-8",
                )
                with self.assertRaisesRegex(ci_contracts.CiContractError, "Dependabot version-update pull requests"):
                    ci_contracts.check(root)

    def test_review_must_not_call_the_compare_api(self) -> None:
        """Submit never publishes PR-head snapshots, so compare cannot be complete."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/dependency-review.yml"
            path.write_text(
                path.read_text(encoding="utf-8")
                + "\n      - run: curl $GITHUB_API_URL/repos/x/y/dependency-graph/compare/a...b\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "compare API"):
                ci_contracts.check(root)

    def test_generate_must_require_a_complete_snapshot(self) -> None:
        """A PR compare cannot prove the graph; generate must keep the artifact check."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/dependency-graph.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "Require a complete dependency snapshot",
                    "Validate dependency snapshot",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "complete snapshot"):
                ci_contracts.check(root)

    def test_submit_must_not_run_for_pull_request_graphs(self) -> None:
        """A PR whose generate job is skipped still concludes success and emails on submit failure."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/dependency-graph-submit.yml"
            text = path.read_text(encoding="utf-8")
            path.write_text(
                text.replace(
                    "github.event.workflow_run.event == 'push'",
                    "github.event.workflow_run.event == 'pull_request'",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "pull-request graphs"):
                ci_contracts.check(root)

    def test_submit_must_accept_main_workflow_dispatch(self) -> None:
        """GITHUB_TOKEN merges do not fire push generate; dispatch is the backfill."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / ".github/workflows/dependency-graph-submit.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace("push|workflow_dispatch", "push", 1),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "workflow_dispatch"):
                ci_contracts.check(root)

    def test_kover_must_include_core_and_mcp(self) -> None:
        """MCP and core tests must count toward the coverage floor."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / "build.gradle.kts"
            path.write_text(
                path.read_text(encoding="utf-8").replace('kover(project(":mcp"))\n', "", 1),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "Kover must verify"):
                ci_contracts.check(root)

    def test_kover_line_floor_cannot_drop_below_sixty(self) -> None:
        """A 19% floor no longer matches the measured :core+:mcp line coverage."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / "build.gradle.kts"
            path.write_text(
                path.read_text(encoding="utf-8").replace("minBound(60)", "minBound(19)", 1),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "at least 60"):
                ci_contracts.check(root)

    def test_release_currentness_must_prefer_cache_redirector_metadata(self) -> None:
        """A Central-only metadata lookup is how jackson-bom 429 failed Scripts."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / "scripts/release_currentness.py"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "https://cache-redirector.jetbrains.com/repo1.maven.org/maven2",
                    "https://example.test/maven2",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "cache-redirector first"):
                ci_contracts.check(root)

    def test_mcp_module_must_enforce_the_patched_jackson_bom(self) -> None:
        """The MCP Server plugin pulls Jackson 2.19 unless the BOM is enforced."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflows(root)
            path = root / "mcp/build.gradle.kts"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    'add("intellijPlatformDependencies", enforcedPlatform("com.fasterxml.jackson:jackson-bom:2.22.2"))\n',
                    "",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ci_contracts.CiContractError, "Jackson BOM"):
                ci_contracts.check(root)

    def copy_workflows(self, root: Path) -> None:
        """Copy the production workflow set into a temporary repository."""
        production = Path(__file__).resolve().parents[2]
        for relative in (
            ".github/workflows/ci.yml",
            ".github/workflows/conformance.yml",
            ".github/workflows/codeql.yml",
            ".github/workflows/mutation.yml",
            ".github/workflows/dependency-review.yml",
            ".github/workflows/dependency-graph.yml",
            ".github/workflows/dependency-graph-submit.yml",
            ".github/workflows/queue.yml",
            "scripts/ci_scope.py",
            "scripts/codeql-kotlin-compat.init.gradle",
            "scripts/codeql_kotlin_compat_probe.py",
            "scripts/local_gate.py",
            "scripts/release_currentness.py",
            "scripts/run_gradle.sh",
            ".githooks/pre-commit",
            ".githooks/pre-push",
            "docs/CONTRIBUTING.md",
            "settings.gradle.kts",
            "mcp/build.gradle.kts",
            "build.gradle.kts",
            "core/build.gradle.kts",
            "gradle/wrapper/gradle-wrapper.properties",
        ):
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text((production / relative).read_text(encoding="utf-8"), encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
