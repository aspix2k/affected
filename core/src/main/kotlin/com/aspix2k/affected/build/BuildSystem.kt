package com.aspix2k.affected.build

import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicReference

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
    val additionalTestTasks: Set<String> = emptySet(),
    val systemId: String = "",
) {
    val key: String get() = moduleDependencyKey(systemId, root, id)
}

internal fun moduleDependencyKey(systemId: String, root: String, id: String): String =
    if (systemId.isBlank()) "$root|$id" else "$systemId|$root|$id"

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

internal interface WorkspaceChangesBuildSystem {
    fun requiresWorkspace(module: BuildModule, changes: BuildChanges): Boolean
}

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

internal fun nestedBuildRoot(base: File, hasMarker: (File) -> Boolean): File? {
    if (hasMarker(base)) return base
    val children = base.listFiles().orEmpty().filter { child ->
        Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            child.canRead() &&
            child.name !in NESTED_ROOT_SKIP
    }
    return children.singleOrNull(hasMarker)
}

private val NESTED_ROOT_SKIP = setOf(
    ".git",
    ".gradle",
    ".idea",
    ".venv",
    ".cache",
    ".tox",
    "build",
    "coverage",
    "DerivedData",
    "dist",
    "node_modules",
    "obj",
    "out",
    "target",
)

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

internal const val MAX_CACHED_MODULES = 4096

internal fun shouldRetainBuildSnapshot(moduleCount: Int): Boolean =
    moduleCount in 0..MAX_CACHED_MODULES

internal fun <T> AtomicReference<T?>.retainBuildSnapshot(value: T, moduleCount: Int): Boolean {
    if (!shouldRetainBuildSnapshot(moduleCount)) {
        set(null)
        return false
    }
    set(value)
    return true
}
