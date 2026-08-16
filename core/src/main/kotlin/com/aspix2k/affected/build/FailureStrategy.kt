package com.aspix2k.affected.build

import java.nio.file.Files
import java.nio.file.LinkOption
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

internal fun findGradleFailureStrategyScript(classPath: Path): Path? {
    var directory = classPath.toAbsolutePath().normalize().let {
        if (Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)) it else it.parent
    } ?: return null
    repeat(MAX_PLUGIN_PARENT_DEPTH) {
        val script = directory.resolve(FAILURE_STRATEGY_SCRIPT_PATH)
        if (Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(script)) return script
        directory = directory.parent ?: return null
    }
    return null
}

internal fun requiredGradleFailureStrategyScript(classPath: Path): Path {
    val configured = System.getProperty(FAILURE_STRATEGY_SCRIPT_PROPERTY)
        ?.let(Path::of)
        ?.toAbsolutePath()
        ?.normalize()
        ?.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(it) }
    if (configured != null) return configured
    return findGradleFailureStrategyScript(classPath)
        ?: error("Affected Gradle failure strategy script is unavailable; reinstall the plugin")
}

private const val MAX_PLUGIN_PARENT_DEPTH = 5
private const val FAILURE_STRATEGY_SCRIPT_PATH = "agent/affected-failure-strategy.init.gradle"
private const val FAILURE_STRATEGY_SCRIPT_PROPERTY = "affected.test.gradleFailureStrategy"
