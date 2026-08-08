package com.aspix2k.affected

data class AffectedModule(
    val id: String,
    val systemId: String,
    val buildRoot: String,
    val directory: String,
    val testDirectory: String?,
    val testTask: String,
    val compileTask: String,
    val hasTests: Boolean,
    val tasks: Set<String>,
) {
    fun info(): ModuleInfo = ModuleInfo(id, systemId, buildRoot, testTask, compileTask, hasTests)

    fun supports(task: String): Boolean = tasks.contains(task)
}
