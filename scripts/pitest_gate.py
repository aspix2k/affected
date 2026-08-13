"""Fail when PIT reports a surviving mutant."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from xml.etree import ElementTree

MAX_REPORT_BYTES = 16 * 1024 * 1024
KOTLIN_INTRINSICS_NULL_CHECK = "kotlin/jvm/internal/Intrinsics::checkNotNull"


class PitestGateError(RuntimeError):
    """Describe a fail-closed mutation report violation."""


def is_equivalent_kotlin_intrinsic(mutation: ElementTree.Element) -> bool:
    """Compiler-inserted Kotlin null checks do not change observable behaviour."""
    mutator = mutation.findtext("mutator", default="")
    description = mutation.findtext("description", default="")
    return mutator.endswith("VoidMethodCallMutator") and KOTLIN_INTRINSICS_NULL_CHECK in description


def check_all(reports: list[Path]) -> int:
    """Reject an empty report list or any meaningful surviving mutant in any report."""
    if not reports:
        raise PitestGateError("No PIT reports")
    return sum(check(report) for report in reports)


def check(report: Path) -> int:
    """Reject a missing, oversized or meaningfully surviving PIT XML report."""
    if not report.is_file() or report.is_symlink():
        raise PitestGateError(f"Missing PIT report: {report}")
    if report.stat().st_size > MAX_REPORT_BYTES:
        raise PitestGateError(f"PIT report is too large: {report}")
    try:
        root = ElementTree.parse(report).getroot()
    except ElementTree.ParseError as error:
        raise PitestGateError(f"Invalid PIT report: {error}") from error
    candidates = [
        mutation
        for mutation in root.iter("mutation")
        if mutation.get("status") == "SURVIVED"
        or (mutation.get("detected") == "false" and mutation.get("status") not in {"NO_COVERAGE", "TIMED_OUT"})
    ]
    equivalent = [mutation for mutation in candidates if is_equivalent_kotlin_intrinsic(mutation)]
    survivors = [mutation for mutation in candidates if mutation not in equivalent]
    if survivors:
        details = []
        for mutation in survivors[:20]:
            mutator = mutation.findtext("mutator", default="unknown")
            method = mutation.findtext("mutatedMethod", default="unknown")
            details.append(f"{mutator} {method}")
        raise PitestGateError(
            f"PIT reported {len(survivors)} surviving mutant(s): " + "; ".join(details)
        )
    return len(equivalent)


def main(arguments: list[str] | None = None) -> int:
    """Check one or more PIT XML report paths."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", nargs="+", type=Path)
    options = parser.parse_args(arguments)
    try:
        equivalent = check_all(options.reports)
    except PitestGateError as error:
        print(f"PIT gate error: {error}", file=sys.stderr)
        return 1
    if equivalent:
        print(
            f"PIT reports have no surviving mutants. "
            f"Classified {equivalent} compiler-generated Kotlin Intrinsics mutant(s) as equivalent."
        )
    else:
        print("PIT reports have no surviving mutants.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
