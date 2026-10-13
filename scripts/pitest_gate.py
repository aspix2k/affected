"""Fail when PIT reports a surviving mutant."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from xml.etree import ElementTree

MAX_REPORT_BYTES = 16 * 1024 * 1024
KOTLIN_INTRINSICS_NULL_CHECK = "kotlin/jvm/internal/Intrinsics::checkNotNull"
IMPACT_PACKAGE = "com.aspix2k.affected.impact."
KOTLIN_CLOSE_FINALLY = "kotlin/jdk7/AutoCloseableKt::closeFinally"
INLINE_GUARD = "an inlined any or all opens with an empty collection fast path that returns what its loop returns"
COUNT_OVERFLOW = "an inlined count guards against an overflow that a directory listing never reaches"
NO_ROOT = "a key without a root fails the substring or has no URI scheme, which Path.of rejects"
EQUIVALENT_IMPACT_MUTANTS = {
    ("CollectorMapIOKt", "count", "RemoveConditionalMutator_EQUAL_ELSE"): (1, "a negative count never equals a collection size"),
    ("CollectorMapIOKt", "count", "RemoveConditionalMutator_ORDER_IF"): (1, "a negative count never equals a collection size"),
    ("CollectorMapIOKt", "rawValue", "RemoveConditionalMutator_EQUAL_ELSE"): (1, "a blank value is rejected again by decode"),
    ("CollectorMapIOKt", "rawValue", "RemoveConditionalMutator_EQUAL_IF"): (1, "a blank value is rejected again by decode"),
    ("CollectorMapIOKt", "readFile", "ConditionalsBoundaryMutator"): (1, "a one byte file cannot hold a manifest and fails the parsers"),
    ("CollectorMapIOKt", "readFile", "RemoveConditionalMutator_EQUAL_IF"): (1, "a carriage return fails the exact line, value or checksum match that follows"),
    ("CollectorMapIOKt", "readFile", "RemoveConditionalMutator_ORDER_IF"): (1, "an empty file fails the trailing newline check"),
    ("CollectorMapIOKt", "secureDirectory", "RemoveConditionalMutator_EQUAL_ELSE"): (1, "a symlink also fails isDirectory with NOFOLLOW_LINKS"),
    ("CollectorMapIOKt", "secureDirectory", "RemoveConditionalMutator_EQUAL_IF"): (2, "a symlink also fails isDirectory with NOFOLLOW_LINKS; createDirectories fails on non directories and isSymbolicLink rejects links"),
    ("CollectorMapReader", "parseCompleteWorker", "RemoveConditionalMutator_EQUAL_ELSE"): (1, "a short manifest throws on the next indexed read"),
    ("CollectorMapReader", "parseCompleteWorker", "RemoveConditionalMutator_ORDER_IF"): (1, "a short manifest throws on the next indexed read"),
    ("CollectorMapReader", "parseExpected", "RemoveConditionalMutator_ORDER_IF"): (1, "a short manifest throws on the next indexed read"),
    ("CollectorMapReader", "parseMap", "RemoveConditionalMutator_ORDER_IF"): (1, "a short map throws on the next indexed read"),
    ("CollectorMapReader", "read", "RemoveConditionalMutator_EQUAL_ELSE"): (7, INLINE_GUARD + "; " + "worker directory names embed sha256 of the id, so ids are unique"),
    ("CollectorMapReader", "read", "RemoveConditionalMutator_EQUAL_IF"): (6, INLINE_GUARD + "; " + "a blank collector is rejected by the DependencyMapIdentity constructor" + "; " + "worker directory names embed sha256 of the id, so ids are unique"),
    ("CoveringTestsFinder", "find", "RemoveConditionalMutator_EQUAL_ELSE"): (2, INLINE_GUARD),
    ("CoveringTestsFinder", "find", "RemoveConditionalMutator_EQUAL_IF"): (1, INLINE_GUARD),
    ("CoveringTestsFinder", "isCoveredBy", "RemoveConditionalMutator_EQUAL_ELSE"): (2, INLINE_GUARD),
    ("CoveringTestsFinder", "isCoveredBy", "RemoveConditionalMutator_EQUAL_IF"): (1, INLINE_GUARD),
    ("CoveringTestsFinder", "runTarget", "ConditionalsBoundaryMutator"): (1, NO_ROOT),
    ("CoveringTestsFinder", "runTarget", "RemoveConditionalMutator_ORDER_IF"): (1, NO_ROOT),
    ("DependencyMapKt", "hasCompleteMetadata", "RemoveConditionalMutator_EQUAL_IF"): (4, "empty expected workers leave no records, which match rejects; empty expected tests leave no records, which match rejects"),
    ("DependencyMapKt", "isAffectedBy", "RemoveConditionalMutator_EQUAL_ELSE"): (2, INLINE_GUARD),
    ("DependencyMapKt", "isAffectedBy", "RemoveConditionalMutator_EQUAL_IF"): (1, INLINE_GUARD),
    ("DependencyMapKt", "match", "RemoveConditionalMutator_EQUAL_ELSE"): (4, INLINE_GUARD),
    ("DependencyMapKt", "match", "RemoveConditionalMutator_EQUAL_IF"): (2, INLINE_GUARD),
    ("DependencyMapKt", "uniqueById", "RemoveConditionalMutator_EQUAL_ELSE"): (2, INLINE_GUARD),
    ("DependencyMapKt", "uniqueById", "RemoveConditionalMutator_EQUAL_IF"): (1, INLINE_GUARD),
    ("DependencyMapPromotion", "promote", "RemoveConditionalMutator_EQUAL_ELSE"): (4, INLINE_GUARD),
    ("DependencyMapPromotion", "promote", "RemoveConditionalMutator_EQUAL_IF"): (2, INLINE_GUARD),
    ("DependencyMapStore", "parseRecord", "ConditionalsBoundaryMutator"): (1, "a leading separator fails the blank test decode"),
    ("DependencyMapStore", "parseRecord", "RemoveConditionalMutator_EQUAL_ELSE"): (1, "a missing or leading separator fails substring or the blank test decode"),
    ("DependencyMapStore", "parseRecord", "RemoveConditionalMutator_ORDER_IF"): (1, "a missing or leading separator fails substring or the blank test decode"),
    ("DependencyMapStore", "parse", "ConditionalsBoundaryMutator"): (1, "a header only file has no records and fails validation"),
    ("DependencyMapStore", "parse", "RemoveConditionalMutator_EQUAL_ELSE"): (2, "a short file throws on the next indexed read; the checksum equality that follows also rejects non sha256 text"),
    ("DependencyMapStore", "parse", "RemoveConditionalMutator_ORDER_IF"): (1, "a short file throws on the next indexed read"),
    ("DependencyMapStore", "read", "RemoveConditionalMutator_EQUAL_ELSE"): (3, "a blank key has no stored file and fails the identity constructor; a missing file fails readFile and yields null; parse already ties the file name to the task key"),
    ("DependencyMapStore", "read", "RemoveConditionalMutator_EQUAL_IF"): (1, "a blank key has no stored file and fails the identity constructor"),
    ("DependencyMapStore", "readAll", "ConditionalsBoundaryMutator"): (1, COUNT_OVERFLOW),
    ("DependencyMapStore", "readAll", "RemoveConditionalMutator_EQUAL_ELSE"): (2, INLINE_GUARD),
    ("DependencyMapStore", "readAll", "RemoveConditionalMutator_EQUAL_IF"): (1, INLINE_GUARD),
    ("DependencyMapStore", "readAll", "RemoveConditionalMutator_ORDER_ELSE"): (1, COUNT_OVERFLOW),
    ("DependencySelector", "select", "RemoveConditionalMutator_EQUAL_ELSE"): (4, INLINE_GUARD),
    ("DependencySelector", "select", "RemoveConditionalMutator_EQUAL_IF"): (2, INLINE_GUARD),
}


class PitestGateError(RuntimeError):
    """Describe a fail-closed mutation report violation."""


def is_equivalent_kotlin_intrinsic(mutation: ElementTree.Element) -> bool:
    """Compiler-inserted Kotlin null checks do not change observable behaviour."""
    mutator = mutation.findtext("mutator", default="")
    description = mutation.findtext("description", default="")
    return mutator.endswith("VoidMethodCallMutator") and KOTLIN_INTRINSICS_NULL_CHECK in description


def is_equivalent_non_directory_path_entry(mutation: ElementTree.Element) -> bool:
    """A non-directory PATH entry cannot contain a child executable."""
    if mutation.findtext("mutatedClass", default="") != "com.aspix2k.affected.build.ExecutablePathKt":
        return False
    if mutation.findtext("mutatedMethod", default="") != "isReadableDirectory":
        return False
    description = mutation.findtext("description", default="")
    mutator = mutation.findtext("mutator", default="")
    if mutation.findtext("lineNumber") != "54":
        return False
    if "replaced boolean return with true" in description:
        return True
    return mutator.endswith("RemoveConditionalMutator_EQUAL_ELSE") and "replaced equality check with false" in description


def impact_mutant_key(mutation: ElementTree.Element) -> tuple[str, str, str] | None:
    """Return the (class, method, mutator) identity of an impact mutant.

    PIT also reports a source line and an instruction index, but for inlined code the line is a synthetic
    number past the end of the file, and both move with any edit above them.
    """
    mutated_class = mutation.findtext("mutatedClass", default="")
    if not mutated_class.startswith(IMPACT_PACKAGE):
        return None
    return (
        mutated_class[len(IMPACT_PACKAGE):],
        mutation.findtext("mutatedMethod", default=""),
        mutation.findtext("mutator", default="").rsplit(".", 1)[-1],
    )


def is_equivalent_directory_stream_close(mutation: ElementTree.Element) -> bool:
    """Closing the Files.list stream releases a handle without changing any returned value."""
    key = impact_mutant_key(mutation)
    description = mutation.findtext("description", default="")
    return key == ("CollectorMapIOKt", "list", "VoidMethodCallMutator") and KOTLIN_CLOSE_FINALLY in description


def classified_impact_mutants(candidates: list[ElementTree.Element]) -> list[ElementTree.Element]:
    """Return the survivors of every impact method and mutator that stay within their classified count.

    One survivor more than the count leaves the whole group unclassified, because nothing tells the new
    mutant from the classified ones.
    """
    groups: dict[tuple[str, str, str], list[ElementTree.Element]] = {}
    for mutation in candidates:
        key = impact_mutant_key(mutation)
        if key in EQUIVALENT_IMPACT_MUTANTS:
            groups.setdefault(key, []).append(mutation)
    return [
        mutation
        for key, group in groups.items()
        if len(group) <= EQUIVALENT_IMPACT_MUTANTS[key][0]
        for mutation in group
    ]


def unused_classifications(report: Path) -> dict[tuple[str, str, str], int]:
    """Return how many classified survivors each mutated class of a checked report no longer has.

    A spare count would hide a new survivor of the same method and mutator, so it is reported for lowering.
    A mutant that timed out on a loaded machine is spare as well, which is why this is not a failure.
    """
    mutations = list(ElementTree.parse(report).getroot().iter("mutation"))
    mutated = {mutation.findtext("mutatedClass", default="") for mutation in mutations}
    survived = [impact_mutant_key(mutation) for mutation in mutations if mutation.get("status") == "SURVIVED"]
    return {
        key: allowed - survived.count(key)
        for key, (allowed, _reason) in EQUIVALENT_IMPACT_MUTANTS.items()
        if IMPACT_PACKAGE + key[0] in mutated and survived.count(key) < allowed
    }


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
    described = [
        mutation
        for mutation in candidates
        if is_equivalent_kotlin_intrinsic(mutation)
        or is_equivalent_non_directory_path_entry(mutation)
        or is_equivalent_directory_stream_close(mutation)
    ]
    equivalent = described + classified_impact_mutants([m for m in candidates if m not in described])
    survivors = [mutation for mutation in candidates if mutation not in equivalent]
    if survivors:
        details = []
        for mutation in survivors[:20]:
            mutator = mutation.findtext("mutator", default="unknown").rsplit(".", 1)[-1]
            owner = mutation.findtext("mutatedClass", default="unknown").rsplit(".", 1)[-1]
            method = mutation.findtext("mutatedMethod", default="unknown")
            details.append(f"{mutator} {owner}.{method}:{mutation.findtext('lineNumber', default='?')}")
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
    for report in options.reports:
        for key, spare in sorted(unused_classifications(report).items()):
            print(f"PIT gate notice: {spare} classified mutant(s) of {'.'.join(key[:2])} {key[2]} no longer survive")
    if equivalent:
        print(
            f"PIT reports have no surviving mutants. "
            f"Classified {equivalent} equivalent mutant(s)."
        )
    else:
        print("PIT reports have no surviving mutants.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
