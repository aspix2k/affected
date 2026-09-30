package com.aspix2k.affected.build.gradle

import com.aspix2k.affected.build.PerformanceBudgets
import java.io.File
import java.nio.file.Path

internal fun gradleConsumerRoots(root: String, candidateRoots: Set<String>): Set<String> {
    val included = gradlePath(root)
    return candidateRoots.filterTo(LinkedHashSet()) { candidate ->
        val consumer = gradlePath(candidate)
        consumer != included &&
            (
                gradleIsBuildSrcOf(included, consumer) ||
                    (gradleSettingsMayInclude(consumer, included) && gradleMayProducePlugins(included))
                )
    }
}

private fun gradlePath(path: String): Path = File(path).toPath().toAbsolutePath().normalize()

private fun gradleIsBuildSrcOf(included: Path, consumer: Path): Boolean =
    included.fileName?.toString() == "buildSrc" && included.parent == consumer

private fun gradleSettingsMayInclude(consumer: Path, included: Path): Boolean =
    GRADLE_SETTINGS_FILE_NAMES.map { consumer.resolve(it).toFile() }.filter(File::isFile).any { settings ->
        val text = runCatching { settings.readText() }.getOrNull() ?: return@any true
        val literals = INCLUDE_BUILD_LITERAL.findAll(text).toList()
        INCLUDE_BUILD_CALL.findAll(text).count() != literals.size ||
            SETTINGS_SCRIPT_APPLY.containsMatchIn(text) ||
            literals.any { match ->
                val path = match.groupValues[1].ifEmpty { match.groupValues[2] }
                runCatching { consumer.resolve(path).normalize() == included }.getOrDefault(true)
            }
    }

private fun gradleMayProducePlugins(included: Path): Boolean = runCatching {
    val entries = included.toFile().walkTopDown()
        .onEnter { it.name !in GRADLE_SKIPPED_DIRECTORIES }
        .take(PerformanceBudgets.MAX_DIRECTORIES + 1)
        .toList()
    entries.size > PerformanceBudgets.MAX_DIRECTORIES || entries.filter(File::isFile).any { file ->
        when {
            file.name in GRADLE_BUILD_SCRIPT_NAMES -> GRADLE_PLUGIN_MARKER.containsMatchIn(file.readText())
            file.name.endsWith(".gradle.kts") || file.name.endsWith(".gradle") ->
                GRADLE_PRECOMPILED_SCRIPT_DIRECTORY.containsMatchIn(file.invariantSeparatorsPath)
            else -> false
        }
    }
}.getOrDefault(true)

private val GRADLE_SKIPPED_DIRECTORIES = setOf("build", ".gradle", ".git", ".idea", "node_modules")
private val GRADLE_BUILD_SCRIPT_NAMES = setOf("build.gradle.kts", "build.gradle")
private val GRADLE_PLUGIN_MARKER = Regex("""kotlin-dsl|java-gradle-plugin|version-catalog|\bgradlePlugin\s*\{""")
private val GRADLE_PRECOMPILED_SCRIPT_DIRECTORY = Regex("""/src/main/(kotlin|groovy)/""")
private val GRADLE_SETTINGS_FILE_NAMES = listOf("settings.gradle.kts", "settings.gradle")
private val INCLUDE_BUILD_CALL = Regex("""\bincludeBuild\b""")
private val INCLUDE_BUILD_LITERAL = Regex("""\bincludeBuild\s*\(?\s*(?:"([^"$\\]+)"|'([^'\\]+)')""")
private val SETTINGS_SCRIPT_APPLY = Regex("""\bapply\s*\(?\s*from\b""")
