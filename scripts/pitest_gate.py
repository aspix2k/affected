"""Fail when PIT reports a surviving mutant."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from xml.etree import ElementTree

MAX_REPORT_BYTES = 16 * 1024 * 1024


class PitestGateError(RuntimeError):
    """Describe a fail-closed mutation report violation."""


def check(report: Path) -> None:
    """Reject a missing, oversized or surviving PIT XML report."""
    if not report.is_file() or report.is_symlink():
        raise PitestGateError(f"Missing PIT report: {report}")
    if report.stat().st_size > MAX_REPORT_BYTES:
        raise PitestGateError(f"PIT report is too large: {report}")
    try:
        root = ElementTree.parse(report).getroot()
    except ElementTree.ParseError as error:
        raise PitestGateError(f"Invalid PIT report: {error}") from error
    survivors = [
        mutation
        for mutation in root.iter("mutation")
        if mutation.get("status") == "SURVIVED"
        or (mutation.get("detected") == "false" and mutation.get("status") not in {"NO_COVERAGE", "TIMED_OUT"})
    ]
    if survivors:
        details = []
        for mutation in survivors[:20]:
            mutator = mutation.findtext("mutator", default="unknown")
            method = mutation.findtext("mutatedMethod", default="unknown")
            details.append(f"{mutator} {method}")
        raise PitestGateError(
            f"PIT reported {len(survivors)} surviving mutant(s): " + "; ".join(details)
        )


def main(arguments: list[str] | None = None) -> int:
    """Check one PIT XML report path."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    options = parser.parse_args(arguments)
    try:
        check(options.report)
    except PitestGateError as error:
        print(f"PIT gate error: {error}", file=sys.stderr)
        return 1
    print("PIT report has no surviving mutants.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
