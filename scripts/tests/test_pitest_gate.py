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

    def test_non_directory_path_entry_mutants_are_equivalent(self) -> None:
        """A file on PATH cannot contain a child executable, so isDirectory fail-open is idle."""
        with TemporaryDirectory() as directory:
            report = Path(directory) / "mutations.xml"
            report.write_text(
                """<mutations>
                  <mutation detected="false" status="SURVIVED">
                    <mutatedClass>com.aspix2k.affected.build.ExecutablePathKt</mutatedClass>
                    <mutatedMethod>isReadableDirectory</mutatedMethod>
                    <lineNumber>54</lineNumber>
                    <mutator>org.pitest.mutationtest.engine.gregor.mutators.returns.BooleanTrueReturnValsMutator</mutator>
                    <description>replaced boolean return with true for com/aspix2k/affected/build/ExecutablePathKt::isReadableDirectory</description>
                  </mutation>
                  <mutation detected="false" status="SURVIVED">
                    <mutatedClass>com.aspix2k.affected.build.ExecutablePathKt</mutatedClass>
                    <mutatedMethod>isReadableDirectory</mutatedMethod>
                    <lineNumber>54</lineNumber>
                    <mutator>org.pitest.mutationtest.engine.gregor.mutators.RemoveConditionalMutator_EQUAL_ELSE</mutator>
                    <description>removed conditional - replaced equality check with false</description>
                  </mutation>
                </mutations>
                """,
                encoding="utf-8",
            )
            self.assertEqual(2, pitest_gate.check(report))

    def test_can_read_true_on_a_directory_still_fails(self) -> None:
        """Skipping canRead on a real directory is not the non-directory equivalent case."""
        with TemporaryDirectory() as directory:
            report = Path(directory) / "mutations.xml"
            report.write_text(
                """<mutations>
                  <mutation detected="false" status="SURVIVED">
                    <mutatedClass>com.aspix2k.affected.build.ExecutablePathKt</mutatedClass>
                    <mutatedMethod>isReadableDirectory</mutatedMethod>
                    <lineNumber>55</lineNumber>
                    <mutator>org.pitest.mutationtest.engine.gregor.mutators.returns.BooleanTrueReturnValsMutator</mutator>
                    <description>replaced boolean return with true for com/aspix2k/affected/build/ExecutablePathKt::isReadableDirectory</description>
                  </mutation>
                </mutations>
                """,
                encoding="utf-8",
            )
            with self.assertRaisesRegex(pitest_gate.PitestGateError, "surviving"):
                pitest_gate.check(report)

    def test_isreadable_early_return_stays_on_the_classified_line(self) -> None:
        """Line 54 is the isDirectory early return the equivalent-mutant matcher keys on."""
        source = Path(__file__).resolve().parents[2] / "engine/src/main/kotlin/com/aspix2k/affected/build/ExecutablePath.kt"
        lines = source.read_text(encoding="utf-8").splitlines()
        self.assertEqual("    if (!isDirectory) return false", lines[53])

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

    def impact_mutation(
        self,
        mutated_class: str,
        method: str,
        line: int,
        index: int,
        mutator: str,
        description: str = "removed conditional - replaced equality check with false",
    ) -> str:
        """Build one surviving impact-package mutation element."""
        return f"""
              <mutation detected="false" status="SURVIVED">
                <mutatedClass>com.aspix2k.affected.impact.{mutated_class}</mutatedClass>
                <mutatedMethod>{method}</mutatedMethod>
                <lineNumber>{line}</lineNumber>
                <mutator>org.pitest.mutationtest.engine.gregor.mutators.{mutator}</mutator>
                <indexes><index>{index}</index></indexes>
                <description>{description}</description>
              </mutation>"""

    def check_impact(self, *mutations: str) -> int:
        """Run the gate over a report holding only the given mutation elements."""
        with TemporaryDirectory() as directory:
            report = Path(directory) / "mutations.xml"
            report.write_text(f"<mutations>{''.join(mutations)}</mutations>", encoding="utf-8")
            return pitest_gate.check(report)

    def test_inline_collection_guard_mutants_are_equivalent(self) -> None:
        """Skipping the Iterable.any fast path leaves the loop to return the same empty result."""
        self.assertEqual(
            3,
            self.check_impact(
                self.impact_mutation("DependencyMapKt", "isAffectedBy", 178, 30, "RemoveConditionalMutator_EQUAL_ELSE"),
                self.impact_mutation("DependencyMapKt", "isAffectedBy", 178, 30, "RemoveConditionalMutator_EQUAL_IF"),
                self.impact_mutation("DependencyMapKt", "isAffectedBy", 178, 34, "RemoveConditionalMutator_EQUAL_ELSE"),
            ),
        )

    def test_forcing_the_empty_test_true_in_the_guard_still_fails(self) -> None:
        """Returning the neutral value for a non-empty collection is observable and must stay killed."""
        with self.assertRaisesRegex(pitest_gate.PitestGateError, "1 surviving"):
            self.check_impact(
                self.impact_mutation("DependencyMapKt", "isAffectedBy", 178, 34, "RemoveConditionalMutator_EQUAL_IF"),
            )

    def test_classification_does_not_follow_a_drifted_instruction(self) -> None:
        """The same method and line with another instruction index is a new, unclassified mutant."""
        with self.assertRaisesRegex(pitest_gate.PitestGateError, "1 surviving"):
            self.check_impact(
                self.impact_mutation("DependencyMapKt", "isAffectedBy", 178, 31, "RemoveConditionalMutator_EQUAL_ELSE"),
            )

    def test_redundant_validation_mutants_are_equivalent(self) -> None:
        """A removed check that another check on the same path repeats is classified by exact position."""
        self.assertEqual(
            1,
            self.check_impact(
                self.impact_mutation("CollectorMapIOKt", "readFile", 368, 33, "RemoveConditionalMutator_ORDER_IF"),
            ),
        )

    def test_redundant_validation_with_another_mutator_still_fails(self) -> None:
        """Only the listed mutator at a listed position is classified."""
        with self.assertRaisesRegex(pitest_gate.PitestGateError, "1 surviving"):
            self.check_impact(
                self.impact_mutation("CollectorMapIOKt", "readFile", 368, 33, "RemoveConditionalMutator_EQUAL_IF"),
            )

    def test_directory_stream_close_is_equivalent_only_for_close_finally(self) -> None:
        """Dropping the stream close is idle, but another void call at that position is not."""
        close = self.impact_mutation(
            "CollectorMapIOKt",
            "list",
            361,
            68,
            "VoidMethodCallMutator",
            "removed call to kotlin/jdk7/AutoCloseableKt::closeFinally",
        )
        other = self.impact_mutation(
            "CollectorMapIOKt", "list", 361, 68, "VoidMethodCallMutator", "removed call to java/io/File::delete"
        )
        self.assertEqual(1, self.check_impact(close))
        with self.assertRaisesRegex(pitest_gate.PitestGateError, "1 surviving"):
            self.check_impact(other)

    def test_classified_impact_positions_stay_on_their_source_lines(self) -> None:
        """The impact classifications key on lines that must keep holding the checks they describe."""
        root = Path(__file__).resolve().parents[2] / "engine/src/main/kotlin/com/aspix2k/affected/impact"
        io = (root / "CollectorMapIO.kt").read_text(encoding="utf-8").splitlines()
        map_source = (root / "DependencyMap.kt").read_text(encoding="utf-8").splitlines()
        self.assertEqual("    require(size in 1..MAX_FILE_SIZE)", io[367])
        self.assertEqual("    require(!Files.isSymbolicLink(absolute))", io[354])
        self.assertEqual("        require(expectedWorkers.size == parsed.size)", io[37])
        self.assertIn("expectedWorkers.isNotEmpty() && expectedTestClasses.isNotEmpty()", map_source[142])
        self.assertIn("dependencies.any", map_source[159])
        self.assertIn("grouped.values.any", map_source[163])


if __name__ == "__main__":
    unittest.main()
