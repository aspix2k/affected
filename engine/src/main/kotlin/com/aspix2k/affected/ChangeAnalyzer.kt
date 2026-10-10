package com.aspix2k.affected

import com.aspix2k.affected.build.isJvmTestSourceSet
import com.aspix2k.affected.build.resolveExecutable
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ChangeAnalyzer
@Suppress("LongParameterList")
constructor(
    internal val projectDir: File,
    private val baseBranch: String?,
    private val sourceExtensions: Set<String> = DEFAULT_EXTENSIONS,
    private val includeAllFiles: Boolean = false,
    private val gitExecutable: String = "git",
    private val sourceFileNames: Set<String> = emptySet(),
    private val sourceRoots: Set<String> = emptySet(),
    private val excludedRoots: Set<String> = emptySet(),
    private val environment: Map<String, String> = System.getenv(),
    private val declaredPaths: Set<String>? = emptySet(),
    private val assumedPaths: List<String> = emptyList(),
    private val checkCanceled: () -> Unit = {},
) {

    class GitFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val resolvedBase: Pair<String, String>? by lazy(::resolveBase)

    private val mergeBase: String? get() = resolvedBase?.second

    fun isUsable(): Boolean = projectDir.isDirectory && try {
        run("rev-parse", "--git-dir").exitCode == 0
    } catch (error: GitFailure) {
        if (error.cause is IOException) false else throw error
    }

    fun resolvedBranch(): String? = resolvedBase?.first

    fun comparisonBase(): String? = mergeBase

    fun modifiedAgainstBase(): Set<File> {
        val base = mergeBase ?: return emptySet()
        val paths = gitFields("diff", "--name-status", "--no-renames", "--relative", "-z", base)
            .chunked(2)
            .mapNotNull { (status, path) -> path.takeIf { status == "M" } }
        return keepSources(paths).toSet()
    }

    private val changedPaths: List<String> by lazy {
        val committed = mergeBase?.let { gitFields("diff", "--name-only", "--no-renames", "--relative", "-z", it) }
        val local = when {
            committed != null -> emptyList()
            hasHead -> gitFields("diff", "--name-only", "--no-renames", "--relative", "-z", HEAD)
            else -> gitFields("ls-files", "-z")
        }
        val untracked = gitFields("ls-files", "--others", "--exclude-standard", "-z")
            .filterNot { it.startsWith(IDE_DIRECTORY) || isUnderAny(it, excludedRoots) }
        (committed.orEmpty() + local + untracked + assumedPaths).distinct()
    }

    private val hasHead: Boolean by lazy { run("rev-parse", "--verify", "-q", HEAD).exitCode == 0 }

    fun hasComparisonBase(): Boolean = mergeBase != null || !hasHead

    fun againstBase(): List<File> = keepSources(changedPaths)

    fun againstBase(accepts: (String) -> Boolean): List<File> =
        changedPaths.filter(accepts).map { File(projectDir, it) }

    fun apiTouchedAmong(files: Collection<File>): Set<File> {
        val relatives = files.associateWith(::relativePath)
        val candidates = relatives.values.filterNotNull().filter(::needsApiCheck).filterNot(::isGitQuoted)
        val diffs = diffByFile(candidates)
        return files.filterTo(HashSet()) { apiTouched(it, relatives[it], diffs) }
    }

    internal fun keepSources(paths: Collection<String>): List<File> {
        return paths
            .filter { path ->
                declaredPaths == null || path in declaredPaths ||
                    isCollectedSource(path, includeAllFiles, sourceExtensions, sourceFileNames, sourceRoots)
            }
            .map { File(projectDir, it) }
            .distinct()
    }

    private fun diffByFile(paths: List<String>): Map<String, List<String>> {
        if (paths.isEmpty()) return emptyMap()
        val ref = mergeBase ?: HEAD.takeIf { hasHead } ?: return emptyMap()
        return pathChunks(paths.distinct()).fold(HashMap()) { diffs, chunk ->
            diffs.apply {
                putAll(
                    parseDiff(
                        git(DIFF_ARGUMENTS + ref + "--" + chunk)
                    )
                )
            }
        }
    }

    private fun parseDiff(patch: String): Map<String, List<String>> {
        val diffs = HashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        var oldPath = ""
        var inHeader = false
        for (line in patch.lines()) {
            when {
                line.startsWith("diff --git ") -> {
                    inHeader = true
                    current = null
                }
                inHeader && line.startsWith("--- ") -> oldPath = diffPath(line)
                inHeader && line.startsWith("+++ ") -> {
                    val path = diffPath(line).takeIf { it != DEV_NULL } ?: oldPath
                    current = diffs.getOrPut(path) { mutableListOf() }
                }
                line.startsWith("@@") -> inHeader = false
                !inHeader -> current?.add(line)
            }
        }
        return diffs
    }

    private fun diffPath(header: String): String = header.drop(HEADER_PREFIX_LENGTH).removeSuffix("\t")

    private fun relativePath(file: File): String? =
        runCatching { file.relativeTo(projectDir).invariantSeparatorsPath }.getOrNull()

    private fun needsApiCheck(relative: String): Boolean =
        !isJvmTestSourceSet(relative) && relative.substringAfterLast('.', "") in API_SOURCE_EXTENSIONS

    private fun apiTouched(file: File, relative: String?, diffs: Map<String, List<String>>): Boolean {
        if (relative == null) return true
        if (!needsApiCheck(relative)) return false
        if (isGitQuoted(relative)) return true

        val diff = diffs[relative].orEmpty()

        if (diff.isEmpty()) {
            if (!file.isFile) return false
            return file.useLines { lines -> lines.any(ApiSignatures::isPublicDeclaration) }
        }

        return ApiSignatures.changed(diff)
    }

    private fun resolveBase(): Pair<String, String>? = candidateBranches()
        .flatMap { branch -> listOf("origin/$branch", branch).map { ref -> branch to ref } }
        .firstNotNullOfOrNull { (branch, ref) ->
            run("merge-base", HEAD, ref).takeIf { it.exitCode == 0 }?.stdout?.trim()?.ifEmpty { null }
                ?.let { branch to it }
        }

    private fun candidateBranches(): List<String> =
        (listOfNotNull(baseBranch) + listOfNotNull(remoteDefaultBranch()) + FALLBACK_BRANCHES)
            .distinct()
            .filter { it.isNotBlank() }

    private fun remoteDefaultBranch(): String? =
        run("symbolic-ref", "--quiet", "--short", REMOTE_HEAD).takeIf { it.exitCode == 0 }
            ?.stdout?.trim()?.removePrefix("origin/")?.ifEmpty { null }

    private fun gitFields(vararg args: String): List<String> =
        git(*args).split(NUL).filter(String::isNotEmpty)

    private fun git(vararg args: String): String = git(args.asList())

    private fun git(args: List<String>): String {
        val output = run(args)
        if (output.exitCode != 0) throw GitFailure("git ${args.first()} exited with ${output.exitCode}")
        return output.stdout
    }

    private fun run(vararg args: String): GitOutput = run(args.asList())

    private fun run(args: List<String>): GitOutput = try {
        if (!projectDir.isDirectory) throw GitFailure("$projectDir is not a directory")
        val executable = resolveExecutable(
            gitExecutable,
            environment["PATH"] ?: environment["Path"],
            environment["PATHEXT"],
        )
        capture(
            listOf(executable, "-c", "core.quotePath=false") + args,
            projectDir,
            environment + (LITERAL_PATHSPECS to "1"),
            GIT_TIMEOUT_MILLIS,
            checkCanceled,
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: GitFailure) {
        throw error
    } catch (error: TimeoutException) {
        throw GitFailure("git ${args.first()} did not finish")
    } catch (error: IOException) {
        throw GitFailure("git ${args.first()} could not run: ${error.message}", error)
    }

    companion object {
        internal fun capture(
            command: List<String>,
            directory: File,
            environment: Map<String, String>,
            timeoutMillis: Long,
            checkCanceled: () -> Unit,
        ): GitOutput {
            checkCanceled()
            val builder = ProcessBuilder(command)
                .directory(directory)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment().apply {
                clear()
                putAll(environment)
            }
            val process = builder.start()
            val output = FutureTask { process.inputStream.readAllBytes() }
            Thread(output, "affected-git-output").apply { isDaemon = true }.start()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            try {
                awaitExit(process, deadline, checkCanceled)
                val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
                val bytes = output.get(remaining, TimeUnit.NANOSECONDS)
                return GitOutput(process.exitValue(), String(bytes, Charsets.UTF_8))
            } catch (error: InterruptedException) {
                throw CancellationException("git was interrupted").apply { initCause(error) }
            } catch (error: ExecutionException) {
                throw IOException(error.cause?.message, error.cause)
            } finally {
                if (process.isAlive) terminate(process)
            }
        }

        private fun awaitExit(process: Process, deadline: Long, checkCanceled: () -> Unit) {
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                checkCanceled()
                if (System.nanoTime() - deadline >= 0) throw TimeoutException()
            }
        }

        private fun terminate(process: Process) {
            val children = process.descendants().toList()
            process.destroy()
            children.forEach(ProcessHandle::destroy)
            if (process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS)) return
            children.forEach(ProcessHandle::destroyForcibly)
            process.destroyForcibly()
        }

        private fun pathChunks(paths: List<String>): List<List<String>> {
            val chunks = mutableListOf<MutableList<String>>()
            var length = 0
            for (path in paths) {
                if (chunks.isEmpty() || length + path.length > PATHSPEC_CHUNK_LENGTH) {
                    chunks.add(mutableListOf())
                    length = 0
                }
                chunks.last().add(path)
                length += path.length + 1
            }
            return chunks
        }

        private fun isGitQuoted(relative: String): Boolean =
            relative.any { it == '"' || it == '\\' || it.code < SPACE || it.code == DELETE }

        val DEFAULT_EXTENSIONS = setOf("kt", "kts", "java", "xml", "json", "pro")

        private val API_SOURCE_EXTENSIONS = setOf("kt", "java", "scala", "groovy")

        private val DIFF_ARGUMENTS = listOf(
            "diff", "-U0", "--no-renames", "--relative", "--no-prefix", "--no-color", "--no-ext-diff", "--no-textconv",
        )
        private const val HEAD = "HEAD"
        private const val REMOTE_HEAD = "refs/remotes/origin/HEAD"
        private const val NUL = '\u0000'
        private const val PATHSPEC_CHUNK_LENGTH = 16_000
        private const val SPACE = 0x20
        private const val DELETE = 0x7f
        private const val DEV_NULL = "/dev/null"
        private const val HEADER_PREFIX_LENGTH = 4
        private val GIT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(90)
        private const val POLL_MILLIS = 100L
        private const val TERMINATION_GRACE_MILLIS = 5_000L
        private const val LITERAL_PATHSPECS = "GIT_LITERAL_PATHSPECS"
        private const val IDE_DIRECTORY = ".idea/"
        private val FALLBACK_BRANCHES = listOf("develop", "main", "master")
    }
}

internal class GitOutput(val exitCode: Int, val stdout: String)

fun isProjectDocumentation(path: String): Boolean {
    val normalized = path.replace('\\', '/').trimStart('/')
    val name = normalized.substringAfterLast('/').lowercase()
    val parent = normalized.substringBeforeLast('/', "")
    return name in PROJECT_DOCUMENTATION_NAMES && (parent.isEmpty() || parent == "docs")
}

internal fun isCollectedSource(
    path: String,
    includeAllFiles: Boolean,
    extensions: Set<String>,
    names: Set<String>,
    sourceRoots: Set<String> = emptySet(),
): Boolean {
    if (isProjectDocumentation(path) || path.replace('\\', '/') == DeclaredDependencies.LOCATION) return false
    val fileName = path.substringAfterLast('/').substringAfterLast('\\')
    if (fileName in DESKTOP_METADATA_NAMES) return false
    return includeAllFiles ||
        path.substringAfterLast('.', "").lowercase() in extensions ||
        fileName in names ||
        isUnderAny(path, sourceRoots)
}

internal fun filesOutsideSources(
    files: Collection<File>,
    projectDir: File,
    extensions: Set<String>,
    names: Set<String>,
    sourceRoots: Set<String>,
): Set<File> = files.filterTo(HashSet()) { file ->
    val relative = runCatching { file.relativeTo(projectDir).invariantSeparatorsPath }
        .getOrDefault(file.invariantSeparatorsPath)
    !isCollectedSource(relative, includeAllFiles = false, extensions, names, sourceRoots)
}

private fun isUnderAny(path: String, roots: Set<String>): Boolean {
    if (roots.isEmpty()) return false
    return generateSequence(path.replace('\\', '/').substringBeforeLast('/', "")) { it.substringBeforeLast('/', "") }
        .takeWhile(String::isNotEmpty)
        .any(roots::contains)
}

private val DESKTOP_METADATA_NAMES = setOf(".DS_Store", "Thumbs.db", "desktop.ini")

private val PROJECT_DOCUMENTATION_NAMES = setOf(
    "readme.md",
    "license",
    "license.md",
    "licence",
    "licence.md",
    "changelog.md",
    "contributing.md",
    "security.md",
    "code_of_conduct.md",
    "support.md",
    "privacy.md",
)
