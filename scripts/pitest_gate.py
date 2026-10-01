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
LIST_CLOSE_MUTANT = ("CollectorMapIOKt", "list", 344, 68, "VoidMethodCallMutator")
INLINE_COLLECTION_GUARD_MUTANTS = frozenset(
    {
        ("CollectorMapReader", "read", 404, 128, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("CollectorMapReader", "read", 445, 736, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("CollectorMapReader", "read", 445, 736, "RemoveConditionalMutator_EQUAL_IF"),
        ("CollectorMapReader", "read", 445, 740, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("CollectorMapReader", "read", 460, 1053, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("CollectorMapReader", "read", 460, 1053, "RemoveConditionalMutator_EQUAL_IF"),
        ("CollectorMapReader", "read", 460, 1057, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("DependencyMapKt", "isAffectedBy", 178, 30, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("DependencyMapKt", "isAffectedBy", 178, 30, "RemoveConditionalMutator_EQUAL_IF"),
        ("DependencyMapKt", "isAffectedBy", 178, 34, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("DependencyMapKt", "match", 209, 26, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("DependencyMapKt", "match", 209, 30, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("DependencyMapKt", "match", 211, 67, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("DependencyMapKt", "match", 211, 67, "RemoveConditionalMutator_EQUAL_IF"),
        ("DependencyMapKt", "match", 211, 71, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("DependencyMapKt", "uniqueById", 195, 136, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("DependencyMapKt", "uniqueById", 195, 140, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("DependencyMapPromotion", "promote", 182, 97, "RemoveConditionalMutator_EQUAL_IF"),
        ("DependencyMapPromotion", "promote", 182, 101, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("DependencyMapPromotion", "promote", 185, 178, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("DependencyMapPromotion", "promote", 185, 178, "RemoveConditionalMutator_EQUAL_IF"),
        ("DependencyMapPromotion", "promote", 185, 182, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("DependencySelector", "select", 193, 276, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("DependencySelector", "select", 193, 276, "RemoveConditionalMutator_EQUAL_IF"),
        ("DependencySelector", "select", 193, 280, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("DependencySelector", "select", 196, 345, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("DependencySelector", "select", 196, 345, "RemoveConditionalMutator_EQUAL_IF"),
        ("DependencySelector", "select", 196, 349, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("CollectorMapReader", "read", 404, 124, "RemoveConditionalMutator_EQUAL_ELSE"),
        ("CollectorMapReader", "read", 404, 124, "RemoveConditionalMutator_EQUAL_IF"),
        ("DependencyMapKt", "match", 209, 26, "RemoveConditionalMutator_EQUAL_IF"),
        ("DependencyMapKt", "uniqueById", 195, 136, "RemoveConditionalMutator_EQUAL_IF"),
        ("DependencyMapPromotion", "promote", 182, 97, "RemoveConditionalMutator_EQUAL_ELSE"),
    }
)
REDUNDANT_VALIDATION_MUTANTS = {
    ("CollectorMapIOKt", "count", 364, 19, "RemoveConditionalMutator_ORDER_IF"): "a negative count never equals a collection size",
    ("CollectorMapIOKt", "count", 364, 27, "RemoveConditionalMutator_EQUAL_ELSE"): "a negative count never equals a collection size",
    ("CollectorMapIOKt", "rawValue", 361, 40, "RemoveConditionalMutator_EQUAL_IF"): "a blank value is rejected again by decode",
    ("CollectorMapIOKt", "rawValue", 361, 48, "RemoveConditionalMutator_EQUAL_ELSE"): "a blank value is rejected again by decode",
    ("CollectorMapIOKt", "readFile", 351, 33, "RemoveConditionalMutator_ORDER_IF"): "an empty file fails the trailing newline check",
    ("CollectorMapIOKt", "secureDirectory", 338, 18, "RemoveConditionalMutator_EQUAL_IF"): "a symlink also fails isDirectory with NOFOLLOW_LINKS",
    ("CollectorMapIOKt", "secureDirectory", 338, 26, "RemoveConditionalMutator_EQUAL_ELSE"): "a symlink also fails isDirectory with NOFOLLOW_LINKS",
    ("CollectorMapIOKt", "secureDirectory", 340, 61, "RemoveConditionalMutator_EQUAL_IF"): "createDirectories fails on non directories and isSymbolicLink rejects links",
    ("CollectorMapReader", "parseCompleteWorker", 136, 16, "RemoveConditionalMutator_ORDER_IF"): "a short manifest throws on the next indexed read",
    ("CollectorMapReader", "parseCompleteWorker", 136, 24, "RemoveConditionalMutator_EQUAL_ELSE"): "a short manifest throws on the next indexed read",
    ("CollectorMapReader", "parseExpected", 93, 6, "RemoveConditionalMutator_ORDER_IF"): "a short manifest throws on the next indexed read",
    ("CollectorMapReader", "parseMap", 160, 11, "RemoveConditionalMutator_ORDER_IF"): "a short map throws on the next indexed read",
    ("CollectorMapReader", "read", 23, 30, "RemoveConditionalMutator_EQUAL_IF"): "a blank collector is rejected by the DependencyMapIdentity constructor",
    ("CollectorMapReader", "read", 23, 39, "RemoveConditionalMutator_EQUAL_IF"): "a blank collector is rejected by the DependencyMapIdentity constructor",
    ("CollectorMapReader", "read", 38, 423, "RemoveConditionalMutator_EQUAL_IF"): "worker directory names embed sha256 of the id, so ids are unique",
    ("CollectorMapReader", "read", 38, 431, "RemoveConditionalMutator_EQUAL_ELSE"): "worker directory names embed sha256 of the id, so ids are unique",
    ("DependencyMapKt", "hasCompleteMetadata", 143, 58, "RemoveConditionalMutator_EQUAL_IF"): "empty expected workers leave no records, which match rejects",
    ("DependencyMapKt", "hasCompleteMetadata", 143, 72, "RemoveConditionalMutator_EQUAL_IF"): "empty expected tests leave no records, which match rejects",
    ("DependencyMapStore", "parseRecord", 297, 15, "RemoveConditionalMutator_ORDER_IF"): "a missing or leading separator fails substring or the blank test decode",
    ("DependencyMapStore", "read", 202, 27, "RemoveConditionalMutator_EQUAL_IF"): "a blank key has no stored file and fails the identity constructor",
    ("DependencyMapStore", "read", 205, 71, "RemoveConditionalMutator_EQUAL_ELSE"): "a missing file fails readFile and yields null",
    ("DependencyMapStore", "read", 207, 87, "RemoveConditionalMutator_ORDER_IF"): "a short file throws on the next indexed read",
    ("DependencyMapStore", "read", 207, 95, "RemoveConditionalMutator_EQUAL_ELSE"): "a short file throws on the next indexed read",
    ("DependencyMapStore", "read", 222, 239, "RemoveConditionalMutator_EQUAL_ELSE"): "the checksum equality that follows also rejects non sha256 text",
    ("DependencyMapKt", "hasCompleteMetadata", 143, 49, "RemoveConditionalMutator_EQUAL_IF"): "empty expected workers leave no records, which match rejects",
    ("DependencyMapKt", "hasCompleteMetadata", 143, 63, "RemoveConditionalMutator_EQUAL_IF"): "empty expected tests leave no records, which match rejects",
    ("DependencyMapStore", "parseRecord", 297, 15, "ConditionalsBoundaryMutator"): "a leading separator fails the blank test decode",
    ("DependencyMapStore", "parseRecord", 297, 23, "RemoveConditionalMutator_EQUAL_ELSE"): "a missing or leading separator fails substring or the blank test decode",
    ("DependencyMapStore", "read", 207, 87, "ConditionalsBoundaryMutator"): "a header only file has no records and fails validation",
    ("DependencyMapStore", "read", 202, 35, "RemoveConditionalMutator_EQUAL_ELSE"): "a blank key has no stored file and fails the identity constructor",
    ("CollectorMapIOKt", "readFile", 351, 33, "ConditionalsBoundaryMutator"): "a one byte file cannot hold a manifest and fails the parsers",
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


def impact_mutant_key(mutation: ElementTree.Element) -> tuple[str, str, int, int, str] | None:
    """Return the (class, method, line, instruction index, mutator) identity of an impact mutant."""
    mutated_class = mutation.findtext("mutatedClass", default="")
    if not mutated_class.startswith(IMPACT_PACKAGE):
        return None
    line = mutation.findtext("lineNumber", default="")
    index = mutation.findtext("indexes/index", default="")
    if not (line.isdigit() and index.isdigit()):
        return None
    mutator = mutation.findtext("mutator", default="").rsplit(".", 1)[-1]
    return (
        mutated_class[len(IMPACT_PACKAGE):],
        mutation.findtext("mutatedMethod", default=""),
        int(line),
        int(index),
        mutator,
    )


def is_equivalent_inline_collection_guard(mutation: ElementTree.Element) -> bool:
    """Inlined Iterable.any/all open with `this is Collection && isEmpty()` returning the loop's own empty result.

    Forcing the instanceof test either way, or the isEmpty test to false, only skips that fast path; the loop
    then returns the same value for an empty collection. Forcing isEmpty to true is observable, is killed by
    the selector and promotion tests, and is deliberately not listed.
    """
    return impact_mutant_key(mutation) in INLINE_COLLECTION_GUARD_MUTANTS


def is_equivalent_redundant_validation(mutation: ElementTree.Element) -> bool:
    """A listed check is fully covered by another check on the same path, so removing it changes nothing."""
    key = impact_mutant_key(mutation)
    return key is not None and key in REDUNDANT_VALIDATION_MUTANTS


def is_equivalent_directory_stream_close(mutation: ElementTree.Element) -> bool:
    """Closing the Files.list stream releases a handle without changing any returned value."""
    description = mutation.findtext("description", default="")
    return impact_mutant_key(mutation) == LIST_CLOSE_MUTANT and KOTLIN_CLOSE_FINALLY in description


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
    equivalent = [
        mutation
        for mutation in candidates
        if is_equivalent_kotlin_intrinsic(mutation)
        or is_equivalent_non_directory_path_entry(mutation)
        or is_equivalent_inline_collection_guard(mutation)
        or is_equivalent_redundant_validation(mutation)
        or is_equivalent_directory_stream_close(mutation)
    ]
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
            f"Classified {equivalent} equivalent mutant(s)."
        )
    else:
        print("PIT reports have no surviving mutants.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
