package com.aspix2k.affected.build

import java.nio.file.Path

internal fun continuesAfterFailure(stopAfterFirstFailure: Boolean): Boolean = !stopAfterFirstFailure

internal fun gradleInvocationArguments(
    existing: List<String>,
    stopAfterFirstFailure: Boolean,
    failureStrategyScript: Path? = null,
): List<String> = if (stopAfterFirstFailure) {
    existing + listOf(
        "--init-script",
        requireNotNull(failureStrategyScript) { "Affected Gradle failure strategy script is unavailable" }.toString(),
    )
} else {
    existing + "--continue"
}

internal fun gradleInvocationArguments(
    existing: List<String>,
    selection: GradleTaskSelection,
    stopAfterFirstFailure: Boolean,
    failureStrategyScript: Path? = null,
): List<String> = gradleInvocationArguments(
    existing + selection.diagnosticArguments,
    stopAfterFirstFailure,
    failureStrategyScript,
)

internal fun mavenInvocationArguments(
    existing: List<String>,
    stopAfterFirstFailure: Boolean,
): List<String> = existing + if (stopAfterFirstFailure) "--fail-fast" else "--fail-at-end"
