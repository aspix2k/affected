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


if __name__ == "__main__":
    unittest.main()
