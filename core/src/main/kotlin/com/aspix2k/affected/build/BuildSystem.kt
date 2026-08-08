package com.aspix2k.affected.build

import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project

data class BuildModule(
    val id: String,
    val root: String,
    val contentRoots: List<String>,
    val testTask: String,
    val compileTask: String?,
    val hasTests: Boolean,
    val dependencies: Set<String> = emptySet(),
    val extraTasks: Set<String> = emptySet(),
) {
    val key: String get() = "$root|$id"
}

interface BuildSystem {

    val id: String

    val sourceExtensions: Set<String>

    fun isPresent(project: Project): Boolean

    fun modules(project: Project): List<BuildModule>

    fun run(project: Project, root: String, tasks: List<String>)

    fun runAndWait(project: Project, root: String, tasks: List<String>): Boolean
}

internal interface SuspendingBuildSystem : BuildSystem {
    suspend fun modulesSuspending(project: Project): List<BuildModule> = modules(project)

    suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean

    override fun runAndWait(project: Project, root: String, tasks: List<String>): Boolean =
        runBlockingCancellable { runAndWaitSuspending(project, root, tasks) }
}
