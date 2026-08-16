package com.aspix2k.affected.build

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@ConsistentCopyVisibility
internal data class PlannedExecutionRoot private constructor(
    internal val path: Path,
    private val identity: ExecutionRootIdentity?,
    private val failure: String?,
) {

    fun bind(projectRoot: Path): ExecutionRootGuard {
        val normalizedProject = normalize(projectRoot)
        return ExecutionRootGuard(
            planned = this,
            requestedProjectPath = normalizedProject,
            projectRoot = captureIdentity(normalizedProject),
        )
    }

    companion object {
        fun capture(path: Path, afterFirstRead: () -> Unit = {}): PlannedExecutionRoot {
            val normalized = normalize(path)
            val result = captureIdentity(normalized, afterFirstRead)
            return PlannedExecutionRoot(normalized, result.identity, result.failure)
        }
    }

    internal fun identity(): ExecutionRootIdentity? = identity

    internal fun failure(): String? = failure
}

@ConsistentCopyVisibility
internal data class ExecutionRootGuard internal constructor(
    private val planned: PlannedExecutionRoot,
    private val requestedProjectPath: Path,
    private val projectRoot: IdentityResult,
    private val parent: ExecutionRootGuard? = null,
    private val chainRoot: Path? = null,
    private val fixedFailure: String? = null,
) {

    val path: Path get() = planned.path

    internal val projectPath: Path get() = requestedProjectPath

    fun validationFailure(): String? {
        parent?.validationFailure()?.let { return it }
        fixedFailure?.let { return it }
        planned.failure()?.let { return it }
        projectRoot.failure?.let { return "opened project root ${projectRoot.failure}" }
        val projectIdentity = projectRoot.identity ?: return "opened project root identity is unavailable"
        return currentValidationFailure(projectIdentity)
    }

    private fun currentValidationFailure(projectIdentity: ExecutionRootIdentity): String? {
        if (parent == null && !path.startsWith(projectIdentity.path)) return "is outside the opened project"
        val chain = chainRoot ?: projectIdentity.path
        if (!path.startsWith(chain)) return "is outside the planned working directory"
        validateChain(chain, path)?.let { return it }
        if (parent == null) {
            val currentProject = captureIdentity(projectIdentity.path)
            currentProject.failure?.let { return "opened project root ${currentProject.failure}" }
            if (currentProject.identity != projectIdentity) return "opened project root changed since planning"
        }
        val current = captureIdentity(path)
        current.failure?.let { return it }
        if (current.identity != planned.identity()) return "changed since planning"
        val currentIdentity = current.identity ?: return "identity is unavailable"
        return realContainmentFailure(projectIdentity, currentIdentity)
    }

    private fun realContainmentFailure(
        projectIdentity: ExecutionRootIdentity,
        currentIdentity: ExecutionRootIdentity,
    ): String? {
        if (!currentIdentity.realPath.startsWith(projectIdentity.realPath)) {
            return "resolves outside the opened project"
        }
        val parentRealPath = parent?.planned?.identity()?.realPath
        if (parentRealPath != null && !currentIdentity.realPath.startsWith(parentRealPath)) {
            return "resolves outside the planned working directory"
        }
        return null
    }

    internal fun <T> withResolverContext(block: () -> T): T = ExecutionRootContext.with(this, block)

    internal fun forWorkingDirectory(directory: Path): ExecutionRootGuard {
        val normalized = normalize(directory)
        if (normalized == path) return this
        validationFailure()?.let { return invalid(normalized, it) }
        val outerIdentity = planned.identity() ?: return invalid(normalized, "planned identity is unavailable")
        val child = PlannedExecutionRoot.capture(normalized)
        val childIdentity = child.identity() ?: return ExecutionRootGuard(
            child,
            requestedProjectPath,
            projectRoot,
            parent = this,
            fixedFailure = child.failure(),
        )
        val anchor = when {
            normalized.startsWith(path) -> path
            normalized.startsWith(outerIdentity.realPath) -> outerIdentity.realPath
            childIdentity.realPath.startsWith(outerIdentity.realPath) -> outerIdentity.realPath
            else -> return invalid(normalized, "is outside the planned working directory")
        }
        return ExecutionRootGuard(child, requestedProjectPath, projectRoot, parent = this, chainRoot = anchor)
    }

    internal fun contains(directory: Path): Boolean {
        val normalized = normalize(directory)
        val outerIdentity = planned.identity() ?: return false
        if (normalized.startsWith(path) || normalized.startsWith(outerIdentity.realPath)) return true
        return runCatching { normalized.toRealPath().startsWith(outerIdentity.realPath) }.getOrDefault(false)
    }

    internal companion object {
        fun invalid(path: Path, reason: String): ExecutionRootGuard {
            val normalized = normalize(path)
            val planned = PlannedExecutionRoot.capture(normalized)
            return ExecutionRootGuard(
                planned,
                normalized,
                captureIdentity(normalized),
                fixedFailure = reason,
            )
        }
    }
}

internal suspend fun <T> withPlannedExecutionRoot(
    planned: PlannedExecutionRoot,
    projectRoot: Path,
    block: suspend () -> T,
): T {
    val guard = planned.bind(projectRoot)
    ActiveExecutionRoots.register(guard)
    return try {
        withContext(ExecutionRootContextElement(guard)) { block() }
    } finally {
        ActiveExecutionRoots.unregister(guard)
    }
}

internal fun executionRootGuard(path: Path, projectRoot: Path? = null): ExecutionRootGuard {
    val normalized = normalize(path)
    val normalizedProject = projectRoot?.let(::normalize)
    ExecutionRootContext.current()?.let { active ->
        if (normalizedProject != null && active.projectPath != normalizedProject) {
            return ExecutionRootGuard.invalid(normalized, "belongs to another opened project")
        }
        return active.forWorkingDirectory(normalized)
    }
    ActiveExecutionRoots.current(normalized, normalizedProject)?.let { return it }
    return PlannedExecutionRoot.capture(normalized).bind(normalizedProject ?: normalized)
}

internal fun projectExecutionRootGuard(path: Path, projectRoot: Path?): ExecutionRootGuard =
    projectRoot?.let { executionRootGuard(path, it) }
        ?: ExecutionRootGuard.invalid(normalize(path), "cannot be contained because the opened project has no root")

internal data class ExecutionRootIdentity(
    val path: Path,
    val realPath: Path,
    val fileKey: String,
    val creationTime: FileTime,
)

internal data class IdentityResult(
    val identity: ExecutionRootIdentity?,
    val failure: String?,
)

private object ExecutionRootContext {
    private val active = ThreadLocal<ExecutionRootGuard?>()

    fun current(): ExecutionRootGuard? = active.get()

    fun replace(guard: ExecutionRootGuard?): ExecutionRootGuard? {
        val previous = active.get()
        if (guard == null) active.remove() else active.set(guard)
        return previous
    }

    fun <T> with(guard: ExecutionRootGuard, block: () -> T): T {
        val previous = active.get()
        active.set(guard)
        return try {
            block()
        } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }
}

private class ExecutionRootContextElement(
    private val guard: ExecutionRootGuard,
) : ThreadContextElement<ExecutionRootGuard?>, AbstractCoroutineContextElement(Key) {

    override fun updateThreadContext(context: CoroutineContext): ExecutionRootGuard? =
        ExecutionRootContext.replace(guard)

    override fun restoreThreadContext(context: CoroutineContext, oldState: ExecutionRootGuard?) {
        ExecutionRootContext.replace(oldState)
    }

    private companion object Key : CoroutineContext.Key<ExecutionRootContextElement>
}

private object ActiveExecutionRoots {
    private val guards = HashMap<Path, MutableMap<ExecutionRootGuard, Int>>()

    fun register(guard: ExecutionRootGuard) = synchronized(guards) {
        val active = guards.getOrPut(guard.path) { HashMap() }
        active[guard] = active.getOrDefault(guard, 0) + 1
    }

    fun unregister(guard: ExecutionRootGuard) = synchronized(guards) {
        val active = guards[guard.path] ?: return@synchronized
        val count = active.getOrDefault(guard, 0)
        if (count <= 1) active.remove(guard) else active[guard] = count - 1
        if (active.isEmpty()) guards.remove(guard.path)
    }

    fun current(path: Path, projectRoot: Path?): ExecutionRootGuard? = synchronized(guards) {
        val exact = guards[path]
            ?.keys
            .orEmpty()
            .filter { projectRoot == null || it.projectPath == projectRoot }
            .distinct()
        if (exact.isNotEmpty()) {
            val guard = exact.singleOrNull()
            return@synchronized guard ?: ExecutionRootGuard.invalid(path, "has ambiguous planned identities")
        }
        val candidates = guards.values
            .flatMap(MutableMap<ExecutionRootGuard, Int>::keys)
            .filter { projectRoot == null || it.projectPath == projectRoot }
            .filter { it.contains(path) }
            .distinct()
        when (candidates.size) {
            0 -> null
            1 -> candidates.single().forWorkingDirectory(path)
            else -> ExecutionRootGuard.invalid(path, "has ambiguous planned identities")
        }
    }
}

private fun captureIdentity(path: Path, afterFirstRead: () -> Unit = {}): IdentityResult {
    validationLimitFailure(path)?.let { return IdentityResult(null, it) }
    return try {
        val beforeResult = readDirectorySnapshot(path)
        beforeResult.failure?.let { return IdentityResult(null, it) }
        val before = beforeResult.snapshot ?: return IdentityResult(null, "identity is unavailable")
        afterFirstRead()
        val realPath = path.toRealPath()
        val afterResult = readDirectorySnapshot(path)
        afterResult.failure?.let { return IdentityResult(null, it) }
        val after = afterResult.snapshot ?: return IdentityResult(null, "identity is unavailable")
        if (before != after) return IdentityResult(null, "changed while its identity was inspected")
        if (!Files.isReadable(path)) return IdentityResult(null, "is not readable")
        IdentityResult(
            ExecutionRootIdentity(path, realPath, before.fileKey, before.creationTime),
            null,
        )
    } catch (_: java.nio.file.NoSuchFileException) {
        IdentityResult(null, "does not exist")
    } catch (_: java.nio.file.AccessDeniedException) {
        IdentityResult(null, "is not readable")
    } catch (_: SecurityException) {
        IdentityResult(null, "is not readable")
    } catch (_: Exception) {
        IdentityResult(null, "could not be validated")
    }
}

private fun readDirectorySnapshot(path: Path): DirectorySnapshotResult {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (attributes.isSymbolicLink || attributes.isOther || Files.isSymbolicLink(path)) {
        return DirectorySnapshotResult(null, "is a link")
    }
    if (!attributes.isDirectory) return DirectorySnapshotResult(null, "is not a directory")
    val fileKey = attributes.fileKey()?.let { "nio:$it" } ?: windowsFileIdentity(path)
        ?: return DirectorySnapshotResult(null, "identity is unavailable")
    return DirectorySnapshotResult(DirectorySnapshot(fileKey, attributes.creationTime()), null)
}

private fun validationLimitFailure(path: Path): String? = when {
    path.nameCount > MAX_EXECUTION_ROOT_SEGMENTS || path.toString().length > MAX_EXECUTION_ROOT_CHARACTERS ->
        "is too deep or long to validate safely"
    else -> null
}

private fun validateChain(projectRoot: Path, executionRoot: Path): String? {
    var current = projectRoot
    val relative = projectRoot.relativize(executionRoot)
    val segments = listOf(projectRoot) + relative.map { segment -> current.resolve(segment).also { current = it } }
    for (segment in segments) {
        val result = captureIdentity(segment)
        result.failure?.let { failure ->
            return if (failure == "is a link") "contains a link at $segment" else "$segment $failure"
        }
    }
    return null
}

private fun normalize(path: Path): Path = path.toAbsolutePath().normalize()

private data class DirectorySnapshot(val fileKey: String, val creationTime: FileTime)

private data class DirectorySnapshotResult(val snapshot: DirectorySnapshot?, val failure: String?)

private const val MAX_EXECUTION_ROOT_SEGMENTS = 256
private const val MAX_EXECUTION_ROOT_CHARACTERS = 32_768
