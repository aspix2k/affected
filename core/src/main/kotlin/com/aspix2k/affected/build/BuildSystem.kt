package com.aspix2k.affected.build

import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

interface BuildSystem {

    val id: String

    val sourceExtensions: Set<String>

    fun isPresent(project: Project): Boolean

    fun modules(project: Project): List<BuildModule>

    fun run(project: Project, root: String, tasks: List<String>)

    fun runAndWait(project: Project, root: String, tasks: List<String>): Boolean

    fun isTestSource(path: String): Boolean = false

    val consumersNeedSignatureChange: Boolean get() = false

    val singleOwnerPerRoot: Boolean get() = false
}

internal interface SuspendingBuildSystem : BuildSystem {
    suspend fun modulesSuspending(project: Project): List<BuildModule> =
        runInterruptible(Dispatchers.IO) { modules(project) }

    suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean

    override fun runAndWait(project: Project, root: String, tasks: List<String>): Boolean =
        runBlockingCancellable { runAndWaitSuspending(project, root, tasks) }
}

internal interface ChangeAwareSuspendingBuildSystem : SuspendingBuildSystem {
    suspend fun runAndWaitSuspending(
        project: Project,
        root: String,
        tasks: List<String>,
        changes: BuildChanges,
    ): Boolean
}
