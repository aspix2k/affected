package com.aspix2k.affected.build

import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

data class BuildModule(
    val id: String,
    val root: String,
    val contentRoots: List<String>,
    val testTask: String,
    val compileTask: String?,
    val hasTests: Boolean,
    val dependencies: Set<String> = emptySet(),
    val extraTasks: Set<String> = emptySet(),
    val executionRoot: String = root,
    val executionId: String = id,
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

internal interface NamedSourceBuildSystem {
    val sourceFileNames: Set<String>
}

internal interface AllFileChangesBuildSystem

internal interface TransitiveTestConsumersBuildSystem

internal interface SuspendingBuildSystem : BuildSystem {
    suspend fun modulesSuspending(project: Project): List<BuildModule> =
        runInterruptible(Dispatchers.IO) { modules(project) }

    suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean

    override fun runAndWait(project: Project, root: String, tasks: List<String>): Boolean =
        runBlockingCancellable { runAndWaitSuspending(project, root, tasks) }
}

data class BuildChanges(
    val files: List<String>,
    val exactSelectionEligible: Set<String>,
    val comparedToBase: Boolean,
)

internal interface ChangeAwareSuspendingBuildSystem : SuspendingBuildSystem {
    suspend fun runAndWaitSuspending(
        project: Project,
        root: String,
        tasks: List<String>,
        changes: BuildChanges,
    ): Boolean
}

internal fun rootFallbackModule(
    root: File,
    testTask: String,
    compileTask: String?,
): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = testTask,
        compileTask = compileTask,
        hasTests = true,
        executionId = ".",
    )
}

internal data class ModuleDiscovery(
    val modules: List<BuildModule>,
    val complete: Boolean,
)

internal fun combineFingerprints(vararg fingerprints: String?): String? =
    combineFingerprints(fingerprints.asIterable())

internal fun combineFingerprints(fingerprints: Iterable<String?>): String? {
    val values = fingerprints.toList()
    return values.takeIf { items -> items.all { it != null } }?.joinToString(":") { it.orEmpty() }
}

internal fun File.isRegularFileNoFollow(): Boolean =
    Files.isRegularFile(toPath(), LinkOption.NOFOLLOW_LINKS)

internal fun failClosedModules(
    root: File,
    testTask: String,
    compileTask: String?,
    discovered: List<BuildModule>?,
): ModuleDiscovery = if (discovered.isNullOrEmpty()) {
    ModuleDiscovery(listOf(rootFallbackModule(root, testTask, compileTask)), complete = false)
} else {
    ModuleDiscovery(discovered, complete = true)
}
