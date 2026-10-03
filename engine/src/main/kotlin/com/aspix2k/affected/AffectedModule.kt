package com.aspix2k.affected

data class AffectedModule(
    val id: String,
    val systemId: String,
    val buildRoot: String,
    val directory: String,
    val testDirectory: String?,
    val testTask: String,
    val compileTask: String?,
    val hasTests: Boolean,
    val tasks: Set<String>,
    val executionRoot: String = buildRoot,
    val executionId: String = id,
) {
    fun info(): ModuleInfo = ModuleInfo(
        id = id,
        systemId = systemId,
        buildRoot = buildRoot,
        testTask = testTask,
        compileTask = compileTask,
        hasTests = hasTests,
        executionRoot = executionRoot,
        executionId = executionId,
    )

    fun supports(task: String): Boolean = tasks.contains(task)
}
