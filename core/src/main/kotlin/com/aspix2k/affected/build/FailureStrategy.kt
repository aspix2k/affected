package com.aspix2k.affected.build

internal fun continuesAfterFailure(stopAfterFirstFailure: Boolean): Boolean = !stopAfterFirstFailure

internal fun gradleInvocationArguments(
    existing: List<String>,
    stopAfterFirstFailure: Boolean,
): List<String> = if (stopAfterFirstFailure) existing else existing + "--continue"

internal fun mavenInvocationArguments(
    existing: List<String>,
    stopAfterFirstFailure: Boolean,
): List<String> = existing + if (stopAfterFirstFailure) "--fail-fast" else "--fail-at-end"
