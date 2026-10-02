package com.aspix2k.affected

import com.aspix2k.affected.build.isJvmTestSourceSet
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ChangeAnalyzer(
    internal val projectDir: File,
    private val baseBranch: String?,
    private val sourceExtensions: Set<String> = DEFAULT_EXTENSIONS,
    private val includeAllFiles: Boolean = false,
    private val gitExecutable: String = "git",
    private val sourceFileNames: Set<String> = emptySet(),
    private val sourceRoots: Set<String> = emptySet(),
    private val excludedRoots: Set<String> = emptySet(),
) {

    class GitFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val resolvedBase: Pair<String, String>? by lazy(::resolveBase)

    private val mergeBase: String? get() = resolvedBase?.second

    fun isUsable(): Boolean = projectDir.isDirectory && try {
        run("rev-parse", "--git-dir").exitCode == 0
    } catch (error: GitFailure) {
        if (error.cause is ExecutionException || error.cause is IOException) false else throw error
    }

    fun resolvedBranch(): String? = resolvedBase?.first

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
        (committed.orEmpty() + local + untracked).distinct()
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
            return file.useLines { lines -> lines.any(::isPublicDeclaration) }
        }

        val removed = signatures(diff, "-")
        val added = signatures(diff, "+")

        return removed != added
    }

    private fun signatures(diff: List<String>, marker: String): Set<String> = diff
        .filter { it.startsWith(marker) }
        .map { it.drop(1) }
        .filter(::isPublicDeclaration)
        .mapTo(HashSet(), ::signatureOf)

    private fun signatureOf(line: String): String {
        val header = line.substringBefore('{')
        var depth = 0
        var typed = false
        var body = header.length
        for ((index, char) in header.withIndex()) {
            when (char) {
                '(', '[', '<' -> depth++
                ')', ']' -> depth--
                '>' -> if (header.getOrNull(index - 1) != '-') depth--
                ':' -> if (depth == 0) typed = true
                '=' -> if (depth == 0) body = minOf(body, index)
            }
        }
        val inferredFromBody = !typed || CONST.containsMatchIn(header)
        return (if (inferredFromBody) header else header.substring(0, body)).trim().replace(WHITESPACE, " ")
    }

    private fun isPublicDeclaration(line: String): Boolean {
        val indent = line.takeWhile { it == ' ' }.length

        if (PARAMETER.matches(line) || CONSTRUCTOR_PROPERTY.matches(line)) return indent <= PARAMETER_INDENT
        if (PRIVATE.containsMatchIn(line)) return false
        if (ENUM_CONSTANT.matches(line)) return indent <= MEMBER_INDENT

        val declaration = DECLARATION.containsMatchIn(line) || TYPED_MEMBER.containsMatchIn(line)
        if (!declaration) return false

        if (indent <= MEMBER_INDENT) return true
        if (indent <= NESTED_MEMBER_INDENT && NESTED_MEMBER.containsMatchIn(line)) return true

        return EXPLICIT_MODIFIER.containsMatchIn(line)
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

    private fun run(vararg args: String): ProcessOutput = run(args.asList())

    private fun run(args: List<String>): ProcessOutput = try {
        if (!projectDir.isDirectory) throw GitFailure("$projectDir is not a directory")
        val commandLine = GeneralCommandLine(listOf(gitExecutable, "-c", "core.quotePath=false") + args)
            .withWorkDirectory(projectDir)
            .withCharset(Charsets.UTF_8)
            .withEnvironment("GIT_LITERAL_PATHSPECS", "1")
        val output = CapturingProcessHandler(commandLine).runProcess(GIT_TIMEOUT_MILLIS)
        ensureFinished(output, args.first())
    } catch (error: CancellationException) {
        throw error
    } catch (error: ProcessCanceledException) {
        throw error
    } catch (error: GitFailure) {
        throw error
    } catch (error: Exception) {
        throw GitFailure("git ${args.first()} could not run: ${error.message}", error)
    }

    companion object {
        internal fun ensureFinished(output: ProcessOutput, command: String): ProcessOutput {
            if (output.isCancelled) throw ProcessCanceledException()
            if (output.isTimeout) throw GitFailure("git $command did not finish")
            return output
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
        private val GIT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(90).toInt()
        private const val IDE_DIRECTORY = ".idea/"
        private val FALLBACK_BRANCHES = listOf("develop", "main", "master")

        private const val MEMBER_INDENT = 4

        private const val NESTED_MEMBER_INDENT = 8

        private const val PARAMETER_INDENT = 8

        private val EXPLICIT_MODIFIER = Regex(
            """\b(public|protected|internal|open|abstract|sealed|override|const|lateinit)\b"""
        )

        private const val ANNOTATIONS = """^\s*(?:@\w+(?:\([^)]*\))?\s*)*"""

        private const val MODIFIERS =
            """(?:public\s+|protected\s+|internal\s+|open\s+|abstract\s+|sealed\s+|final\s+|override\s+|""" +
                """data\s+|value\s+|annotation\s+|enum\s+|inline\s+|suspend\s+|expect\s+|actual\s+|""" +
                """lateinit\s+|const\s+|external\s+|operator\s+|infix\s+|tailrec\s+|static\s+)*"""

        private val DECLARATION = Regex(
            ANNOTATIONS + MODIFIERS +
                """(?:fun|def|val|var|class|interface|object|trait|typealias|constructor|record)\b"""
        )

        private val NESTED_MEMBER = Regex(
            ANNOTATIONS + MODIFIERS + """(?:fun|def|class|interface|object|trait|constructor|record)\b"""
        )

        private val ENUM_CONSTANT = Regex("""^\s*[A-Z][A-Z0-9_]*(?:\(.*\))?\s*[,;]?\s*$""")

        private val CONST = Regex("""\bconst\b""")

        private val WHITESPACE = Regex("""\s+""")

        private val PRIVATE = Regex(ANNOTATIONS + """(?:[a-z]+\s+)*private\b""")

        private val CONSTRUCTOR_PROPERTY = Regex(
            ANNOTATIONS + """(?:[a-z]+\s+)*va[lr]\s+\w+\s*:.*,\s*$"""
        )

        private val TYPED_MEMBER = Regex(
            """^\s*(?:@\w+(?:\([^)]*\))?\s*)*""" +
                """(?:public\s+|protected\s+|static\s+|final\s+|abstract\s+|synchronized\s+|""" +
                """native\s+|default\s+|strictfp\s+|transient\s+|volatile\s+)*""" +
                """[A-Za-z_][\w.<>\[\], ?]*\s+[A-Za-z_]\w*\s*[(;=]"""
        )

        private val PARAMETER = Regex("""^\s*(?:@\w+\s*)*[A-Za-z_]\w*\s*:\s*[\w<>\[\]?., ]+,?\s*$""")
    }
}

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
    if (isProjectDocumentation(path)) return false
    val fileName = path.substringAfterLast('/').substringAfterLast('\\')
    return includeAllFiles ||
        path.substringAfterLast('.', "").lowercase() in extensions ||
        fileName in names ||
        isUnderAny(path, sourceRoots)
}

private fun isUnderAny(path: String, roots: Set<String>): Boolean {
    if (roots.isEmpty()) return false
    return generateSequence(path.replace('\\', '/').substringBeforeLast('/', "")) { it.substringBeforeLast('/', "") }
        .takeWhile(String::isNotEmpty)
        .any(roots::contains)
}

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
