package com.aspix2k.affected.build

internal fun gradleNarrowKmpTasks(tasks: List<String>, changes: BuildChanges?): List<String> {
    if (changes == null || !changes.comparedToBase || changes.files.isEmpty()) return tasks
    val families = changes.files.map(::kmpFamilyForPath)
    if (families.any { it == null || it == KmpFamily.COMMON }) return tasks
    val allowed = families.filterNotNull().toSet()
    val narrowed = tasks.filter { task ->
        val name = task.substringAfterLast(':')
        val family = KMP_TASK_FAMILIES[name]
        family == null || family in allowed
    }
    return narrowed.takeIf { it.any { task -> task != "--tests" && !task.startsWith("--") } } ?: tasks
}

private fun kmpFamilyForPath(raw: String): KmpFamily? {
    val segments = raw.replace('\\', '/').split('/')
    val sourceSet = segments.zipWithNext().firstOrNull { it.first == "src" }?.second ?: return null
    return KMP_SOURCE_SET_FAMILIES.entries.firstOrNull { (prefix, _) ->
        sourceSet == prefix || sourceSet.startsWith(prefix)
    }?.value
}

private enum class KmpFamily { ANDROID, APPLE, JVM, JS, WASM, LINUX, MACOS, COMMON }

private val KMP_SOURCE_SET_FAMILIES = listOf(
    "commonMain" to KmpFamily.COMMON,
    "commonTest" to KmpFamily.COMMON,
    "android" to KmpFamily.ANDROID,
    "ios" to KmpFamily.APPLE,
    "apple" to KmpFamily.APPLE,
    "tvos" to KmpFamily.APPLE,
    "watchos" to KmpFamily.APPLE,
    "jvm" to KmpFamily.JVM,
    "js" to KmpFamily.JS,
    "wasm" to KmpFamily.WASM,
    "linux" to KmpFamily.LINUX,
    "macos" to KmpFamily.MACOS,
    "native" to KmpFamily.COMMON,
    "mingw" to KmpFamily.COMMON,
).toMap()

private val KMP_TASK_FAMILIES = mapOf(
    "testDebugUnitTest" to KmpFamily.ANDROID,
    "iosSimulatorArm64Test" to KmpFamily.APPLE,
    "iosX64Test" to KmpFamily.APPLE,
    "iosArm64Test" to KmpFamily.APPLE,
    "jvmTest" to KmpFamily.JVM,
    "test" to KmpFamily.JVM,
    "jsBrowserTest" to KmpFamily.JS,
    "jsNodeTest" to KmpFamily.JS,
    "wasmJsBrowserTest" to KmpFamily.WASM,
    "linuxX64Test" to KmpFamily.LINUX,
    "macosArm64Test" to KmpFamily.MACOS,
    "macosX64Test" to KmpFamily.MACOS,
)
