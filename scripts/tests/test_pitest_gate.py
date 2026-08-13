"""Tests for the weekly PIT survivor gate."""

from __future__ import annotations

import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from scripts import pitest_gate


class PitestGateTest(unittest.TestCase):
    """Fail closed on surviving mutants and keep no-coverage visible."""

    def test_surviving_mutant_fails(self) -> None:
        """A detected=false SURVIVED mutant is a gate failure."""
        with TemporaryDirectory() as directory:
            report = Path(directory) / "mutations.xml"
            report.write_text(
                """<mutations>
                  <mutation detected="false" status="SURVIVED">
                    <mutator>org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator</mutator>
                    <mutatedMethod>plan</mutatedMethod>
                  </mutation>
                </mutations>
                """,
                encoding="utf-8",
            )
            with self.assertRaisesRegex(pitest_gate.PitestGateError, "surviving"):
                pitest_gate.check(report)

    def test_killed_and_no_coverage_pass(self) -> None:
        """Uncovered mutants stay visible in the report but do not fail the gate."""
        with TemporaryDirectory() as directory:
            report = Path(directory) / "mutations.xml"
            report.write_text(
                """<mutations>
                  <mutation detected="true" status="KILLED">
                    <mutator>org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator</mutator>
                    <mutatedMethod>run</mutatedMethod>
                  </mutation>
                  <mutation detected="false" status="NO_COVERAGE">
                    <mutator>org.pitest.mutationtest.engine.gregor.mutators.NullReturnValsMutator</mutator>
                    <mutatedMethod>icon</mutatedMethod>
                  </mutation>
                </mutations>
                """,
                encoding="utf-8",
            )
            pitest_gate.check(report)

    def test_missing_report_fails(self) -> None:
        """A successful PIT task without XML is not a green gate."""
        with TemporaryDirectory() as directory:
            with self.assertRaisesRegex(pitest_gate.PitestGateError, "Missing"):
                pitest_gate.check(Path(directory) / "missing.xml")

    def test_multiple_reports_fail_if_any_has_a_survivor(self) -> None:
        """Root and core reports are both required and fail closed."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            killed = root / "root.xml"
            survived = root / "core.xml"
            killed.write_text(
                """<mutations>
                  <mutation detected="true" status="KILLED">
                    <mutator>org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator</mutator>
                    <mutatedMethod>run</mutatedMethod>
                  </mutation>
                </mutations>
                """,
                encoding="utf-8",
            )
            survived.write_text(
                """<mutations>
                  <mutation detected="false" status="SURVIVED">
                    <mutator>org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator</mutator>
                    <mutatedMethod>parse</mutatedMethod>
                  </mutation>
                </mutations>
                """,
                encoding="utf-8",
            )
            with self.assertRaisesRegex(pitest_gate.PitestGateError, "surviving"):
                pitest_gate.check_all([killed, survived])

    def test_missing_any_required_report_fails(self) -> None:
        """A core PIT task that produced no XML cannot hide behind a green root report."""
        with TemporaryDirectory() as directory:
            root = Path(directory) / "root.xml"
            root.write_text("<mutations/>", encoding="utf-8")
            with self.assertRaisesRegex(pitest_gate.PitestGateError, "Missing"):
                pitest_gate.check_all([root, Path(directory) / "core.xml"])

    def test_compiler_generated_kotlin_intrinsics_are_equivalent(self) -> None:
        """Void-call mutants on Intrinsics.checkNotNull* do not change behaviour."""
        with TemporaryDirectory() as directory:
            report = Path(directory) / "mutations.xml"
            report.write_text(
                """<mutations>
                  <mutation detected="false" status="SURVIVED">
                    <mutator>org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator</mutator>
                    <mutatedMethod>descend</mutatedMethod>
                    <description>removed call to kotlin/jvm/internal/Intrinsics::checkNotNull</description>
                  </mutation>
                  <mutation detected="false" status="SURVIVED">
                    <mutator>org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator</mutator>
                    <mutatedMethod>descend</mutatedMethod>
                    <description>removed call to kotlin/jvm/internal/Intrinsics::checkNotNullExpressionValue</description>
                  </mutation>
                </mutations>
                """,
                encoding="utf-8",
            )
            self.assertEqual(2, pitest_gate.check(report))

    def test_other_void_call_survivors_still_fail(self) -> None:
        """Only compiler-inserted Kotlin null checks are classified as equivalent."""
        with TemporaryDirectory() as directory:
            report = Path(directory) / "mutations.xml"
            report.write_text(
                """<mutations>
                  <mutation detected="false" status="SURVIVED">
                    <mutator>org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator</mutator>
                    <mutatedMethod>descend</mutatedMethod>
                    <description>removed call to java/io/File::delete</description>
                  </mutation>
                </mutations>
                """,
                encoding="utf-8",
            )
            with self.assertRaisesRegex(pitest_gate.PitestGateError, "surviving"):
                pitest_gate.check(report)

    def test_meaningful_survivor_is_not_hidden_by_equivalent_intrinsics(self) -> None:
        """An Intrinsics classification cannot greenwash a real surviving conditional."""
        with TemporaryDirectory() as directory:
            report = Path(directory) / "mutations.xml"
            report.write_text(
                """<mutations>
                  <mutation detected="false" status="SURVIVED">
                    <mutator>org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator</mutator>
                    <mutatedMethod>descend</mutatedMethod>
                    <description>removed call to kotlin/jvm/internal/Intrinsics::checkNotNull</description>
                  </mutation>
                  <mutation detected="false" status="SURVIVED">
                    <mutator>org.pitest.mutationtest.engine.gregor.mutators.RemoveConditionalMutator_EQUAL_IF</mutator>
                    <mutatedMethod>descend</mutatedMethod>
                    <description>removed conditional - replaced equality check with true</description>
                  </mutation>
                </mutations>
                """,
                encoding="utf-8",
            )
            with self.assertRaisesRegex(pitest_gate.PitestGateError, "1 surviving"):
                pitest_gate.check(report)


if __name__ == "__main__":
    unittest.main()
