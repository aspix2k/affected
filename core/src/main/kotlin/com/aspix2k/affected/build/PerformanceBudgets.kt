package com.aspix2k.affected.build

internal object PerformanceBudgets {
    const val EDIT_TO_READY_PARSE_MS = 10_000L
    const val SCAN_TIME_NS = EDIT_TO_READY_PARSE_MS * 1_000_000L
    const val MAX_DIRECTORIES = 16_384
    const val MAX_DEPTH = 7
    const val MAX_MATCHES = 4097
    const val MAX_FINGERPRINT_FILES = 4096
    const val MAX_MANIFEST_BYTES = 8L * 1024L * 1024L
    const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L
}
