package com.aspix2k.affected.build

import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
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

    fun isTestSource(path: String): Boolean = false

    val consumersNeedSignatureChange: Boolean get() = false

    val singleOwnerPerRoot: Boolean get() = false
}

internal fun pathSegments(path: String): List<String> = path.replace('\\', '/').split('/')

internal fun isJvmTestSource(path: String): Boolean {
    val segments = pathSegments(path)
    return segments.windowed(2).any { it[0] == "src" && it[1] == "test" } ||
        segments.any { it == "androidTest" || it == "androidUnitTest" }
}

internal fun isJvmTestSourceSet(path: String): Boolean {
    val segments = pathSegments(path)
    return segments.windowed(2).any { it[0] == "src" && isTestSourceSetName(it[1]) } ||
        segments.any { it == "androidTest" || it == "androidUnitTest" }
}

private fun isTestSourceSetName(name: String): Boolean =
    name == "test" || name.endsWith("Test") ||
        name != "testFixtures" && name.startsWith("test") && name.getOrNull(TEST_PREFIX_LENGTH)?.isUpperCase() == true

private const val TEST_PREFIX_LENGTH = 4

internal interface NamedSourceBuildSystem {
    val sourceFileNames: Set<String>
}

internal interface AllFileChangesBuildSystem {
    val includeGeneratedFiles: Boolean get() = false
}

internal interface TransitiveTestConsumersBuildSystem

internal interface WorkspaceChangesBuildSystem {
    fun requiresWorkspace(module: BuildModule, changes: BuildChanges): Boolean

    fun consumerRoots(root: String, candidateRoots: Set<String>): Set<String> = emptySet()
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
    return nestedListing(base).directories.singleOrNull(hasMarker)
}

internal fun nestedBuildRoots(base: File, markerNames: Set<String>, hasMarker: (File) -> Boolean): List<File> {
    if (hasMarker(base)) return listOf(base)
    val roots = ArrayList<File>()
    var level = listOf(base)
    var visited = 0
    repeat(NESTED_ROOT_DEPTH) {
        val (found, descend) = level
            .flatMap { nestedListing(it).directories }
            .also { visited += it.size }
            .partition { nestedListing(it).names.any(markerNames::contains) && hasMarker(it) }
        roots += found
        if (visited > PerformanceBudgets.MAX_DIRECTORIES || roots.size > PerformanceBudgets.MAX_NESTED_ROOTS) {
            return emptyList()
        }
        level = descend
    }
    return roots.sortedBy(File::getPath)
}

private fun nestedListing(directory: File): NestedListing {
    val now = System.currentTimeMillis()
    val cached = nestedListings[directory.path]
    if (cached != null && now - cached.checkedAt < LISTING_RECHECK_MS) return cached
    val modified = directory.lastModified()
    if (cached != null && cached.modified == modified) {
        cached.checkedAt = now
        return cached
    }
    val entries = directory.listFiles().orEmpty()
    val listing = NestedListing(
        modified = modified,
        checkedAt = now,
        directories = entries.filter { child ->
            Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                child.canRead() &&
                child.name !in NESTED_ROOT_SKIP
        },
        names = entries.mapTo(HashSet()) { it.name.lowercase() },
    )
    if (now - modified > LISTING_SETTLE_MS) {
        if (nestedListings.size >= MAX_NESTED_LISTINGS) nestedListings.clear()
        nestedListings[directory.path] = listing
    } else {
        nestedListings.remove(directory.path)
    }
    return listing
}

private class NestedListing(
    val modified: Long,
    @Volatile var checkedAt: Long,
    val directories: List<File>,
    val names: Set<String>,
)

private val nestedListings = ConcurrentHashMap<String, NestedListing>()

private const val LISTING_SETTLE_MS = 2_000L
private const val LISTING_RECHECK_MS = 1_000L
private const val MAX_NESTED_LISTINGS = 4 * PerformanceBudgets.MAX_DIRECTORIES

private const val NESTED_ROOT_DEPTH = 3

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

internal fun <T> ConcurrentMap<String, T>.retainBuildSnapshot(key: String, value: T, moduleCount: Int): Boolean {
    if (!shouldRetainBuildSnapshot(moduleCount)) {
        remove(key)
        return false
    }
    put(key, value)
    return true
}
