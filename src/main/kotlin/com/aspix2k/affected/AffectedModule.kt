package com.aspix2k.affected

data class AffectedModule(
    val gradlePath: String,
    val buildRoot: String,
    val directory: String,
    val testDirectory: String?,
    val isAndroid: Boolean,
    val hasTests: Boolean,
    val tasks: Set<String>,
) {
    fun info(): ModuleInfo = ModuleInfo(gradlePath, buildRoot, isAndroid, hasTests)

    fun supports(task: String): Boolean = tasks.contains(task)
}
