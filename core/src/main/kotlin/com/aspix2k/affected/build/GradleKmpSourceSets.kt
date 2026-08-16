package com.aspix2k.affected.build

internal data class GradleTaskSelection(
    val taskNames: List<String>,
    val reasons: List<GradleSelectionReason>,
) {
    val diagnosticOutput: String = reasons.joinToString(separator = "\n", postfix = "\n") { reason ->
        "[Affected] Gradle selection - ${reason.name}: ${reason.description}"
    }.takeIf { reasons.isNotEmpty() }.orEmpty()
}

internal enum class GradleSelectionReason(val description: String) {
    CHANGE_BASE_UNAVAILABLE("change base is unavailable; full target selection retained"),
    BUILD_CONFIGURATION_CHANGE("Gradle configuration changed; full target selection retained"),
    SOURCE_IDENTITY_UNPROVEN("added, deleted or otherwise unproven source keeps module-level selection"),
    COMMON_SOURCE_SET_FAN_OUT("common source-set change retains all target test tasks"),
    UNCLASSIFIED_SOURCE_SET("source set is unclassified; full target selection retained"),
    TASK_FAMILY_UNPROVEN("task family is unproven; full target selection retained"),
    KOTLIN_NATIVE_EXACT_UNSUPPORTED("Kotlin/Native exact selection is unsupported; full target task retained"),
}

internal fun gradleTaskSelection(tasks: List<String>, changes: BuildChanges?): GradleTaskSelection {
    val reasons = LinkedHashSet<GradleSelectionReason>()
    val selectedTasks = narrowKmpTasks(tasks, changes, reasons)
    val explainsSelection = changes != null && changes.files.isNotEmpty() && isKmpSelection(tasks)
    if (explainsSelection && selectedTasks.any(::isUnprovedTestTask)) {
        reasons += GradleSelectionReason.TASK_FAMILY_UNPROVEN
    }
    if (explainsSelection && selectedTasks.any(::isKotlinNativeTestTask)) {
        reasons += GradleSelectionReason.KOTLIN_NATIVE_EXACT_UNSUPPORTED
    }
    val classes = changes?.let(::selectTestNgClasses)
    val withFilters = if (classes == null) selectedTasks else selectedTasks + classes.flatMap { listOf("--tests", it) }
    return GradleTaskSelection(withFilters, reasons.sortedBy { it.ordinal })
}

private fun narrowKmpTasks(
    tasks: List<String>,
    changes: BuildChanges?,
    reasons: MutableSet<GradleSelectionReason>,
): List<String> {
    val changed = changes ?: return tasks
    if (!hasChangedKmpTestSelection(tasks, changed)) return tasks
    val fallback = initialKmpFallback(changed)
    if (fallback != null) {
        reasons += fallback
        return tasks
    }
    if (changed.files.toSet() != changed.exactSelectionEligible) {
        reasons += GradleSelectionReason.SOURCE_IDENTITY_UNPROVEN
    }
    val families = changed.files.map(::kmpFamilyForPath)
    if (families.any { it == KmpFamily.COMMON }) {
        reasons += GradleSelectionReason.COMMON_SOURCE_SET_FAN_OUT
    }
    if (families.any { it == null } && tasks.any(::isKmpTargetTask) && changed.files.any(::isKmpSourceSetPath)) {
        reasons += GradleSelectionReason.UNCLASSIFIED_SOURCE_SET
    }
    if (families.any { it == null || it == KmpFamily.COMMON }) return tasks
    val allowed = families.filterNotNull().toSet()
    val narrowed = tasks.filter { task ->
        val name = task.substringAfterLast(':')
        val family = KMP_TASK_FAMILIES[name]
        family == null || allowed.any { it.covers(family) }
    }
    return narrowed.takeIf { it.any(::isTestTask) } ?: tasks.also {
        reasons += GradleSelectionReason.TASK_FAMILY_UNPROVEN
    }
}

private fun hasChangedKmpTestSelection(tasks: List<String>, changes: BuildChanges): Boolean =
    changes.files.isNotEmpty() && tasks.any(::isTestTask) && isKmpSelection(tasks)

private fun isKmpSelection(tasks: List<String>): Boolean = tasks.any(::isKmpTargetTask)

private fun initialKmpFallback(changes: BuildChanges): GradleSelectionReason? = when {
    !changes.comparedToBase -> GradleSelectionReason.CHANGE_BASE_UNAVAILABLE
    changes.files.any(::isGradleConfiguration) -> GradleSelectionReason.BUILD_CONFIGURATION_CHANGE
    else -> null
}

private fun isTestTask(task: String): Boolean {
    val name = task.substringAfterLast(':')
    return isGradleUnitTestTask(name) && !name.endsWith("Classes", ignoreCase = true)
}

private fun isKmpTargetTask(task: String): Boolean {
    val name = task.substringAfterLast(':')
    return name != "test" && KMP_TASK_FAMILIES[name] != null
}

private fun isUnprovedTestTask(task: String): Boolean =
    isTestTask(task) && KMP_TASK_FAMILIES[task.substringAfterLast(':')] == null

private fun isKotlinNativeTestTask(task: String): Boolean =
    KMP_TASK_FAMILIES[task.substringAfterLast(':')] in KOTLIN_NATIVE_FAMILIES

private fun isGradleConfiguration(raw: String): Boolean {
    val path = raw.replace('\\', '/')
    val name = path.substringAfterLast('/')
    return name == "build.gradle" ||
        name == "build.gradle.kts" ||
        name == "settings.gradle" ||
        name == "settings.gradle.kts" ||
        name == "gradle.properties" ||
        name == "libs.versions.toml" ||
        path.endsWith("/gradle/wrapper/gradle-wrapper.properties")
}

private fun kmpFamilyForPath(raw: String): KmpFamily? {
    val sourceSet = sourceSetForPath(raw) ?: return null
    return KMP_SOURCE_SET_FAMILIES.entries.firstOrNull { (prefix, _) ->
        sourceSet == prefix || sourceSet.startsWith(prefix)
    }?.value
}

private fun isKmpSourceSetPath(raw: String): Boolean {
    val sourceSet = sourceSetForPath(raw) ?: return false
    return sourceSet != "main" && sourceSet != "test" &&
        (sourceSet.endsWith("Main") || sourceSet.endsWith("Test"))
}

private fun sourceSetForPath(raw: String): String? {
    val segments = raw.replace('\\', '/').split('/')
    return segments.zipWithNext().firstOrNull { it.first == "src" }?.second
}

private enum class KmpFamily {
    ANDROID,
    APPLE,
    JVM,
    JS,
    WASM,
    LINUX,
    MACOS,
    MINGW,
    NATIVE,
    COMMON,
    ;

    fun covers(task: KmpFamily): Boolean = when (this) {
        COMMON -> true
        NATIVE -> task == NATIVE || task == LINUX || task == MACOS || task == APPLE || task == MINGW
        else -> this == task
    }
}

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
    "native" to KmpFamily.NATIVE,
    "mingw" to KmpFamily.MINGW,
).toMap()

private val KMP_TASK_FAMILIES = mapOf(
    "testDebugUnitTest" to KmpFamily.ANDROID,
    "testAndroidHostTest" to KmpFamily.ANDROID,
    "testAndroid" to KmpFamily.ANDROID,
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
    "mingwX64Test" to KmpFamily.MINGW,
)

private val KOTLIN_NATIVE_FAMILIES = setOf(
    KmpFamily.APPLE,
    KmpFamily.LINUX,
    KmpFamily.MACOS,
    KmpFamily.MINGW,
    KmpFamily.NATIVE,
)
