package com.aspix2k.affected

import com.aspix2k.affected.build.isJvmTestSource
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
    private val baseBranch: String,
    private val sourceExtensions: Set<String> = DEFAULT_EXTENSIONS,
    private val includeAllFiles: Boolean = false,
    private val gitExecutable: String = "git",
    private val sourceFileNames: Set<String> = emptySet(),
) {

    class GitFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val mergeBase: String? by lazy(::resolveMergeBase)

    fun isUsable(): Boolean = projectDir.isDirectory && try {
        run("rev-parse", "--git-dir").exitCode == 0
    } catch (error: GitFailure) {
        if (error.cause is ExecutionException || error.cause is IOException) false else throw error
    }

    fun hasComparisonBase(): Boolean = mergeBase != null

    fun modifiedAgainstBase(): Set<File> {
        val base = mergeBase ?: return emptySet()
        val paths = gitFields("diff", "--name-status", "--no-renames", "-z", base)
            .chunked(2)
            .mapNotNull { (status, path) -> path.takeIf { status == "M" } }
        return keepSources(paths).toSet()
    }

    fun againstBase(): List<File> {
        val base = mergeBase ?: return emptyList()
        return keepSources(gitFields("diff", "--name-only", "--no-renames", "-z", base))
    }

    fun apiTouchedAmong(files: Collection<File>): Set<File> {
        val relatives = files.associateWith(::relativePath)
        val candidates = relatives.values.filterNotNull().filter(::needsApiCheck).filterNot(::isGitQuoted)
        val diffs = diffByFile(candidates)
        return files.filterTo(HashSet()) { apiTouched(it, relatives[it], diffs) }
    }

    internal fun keepSources(paths: Collection<String>): List<File> {
        return paths
            .filter { path ->
                isCollectedSource(path, includeAllFiles, sourceExtensions, sourceFileNames)
            }
            .map { File(projectDir, it) }
            .distinct()
    }

    private fun diffByFile(paths: List<String>): Map<String, List<String>> {
        if (paths.isEmpty()) return emptyMap()
        val ref = mergeBase ?: HEAD.takeIf { run("rev-parse", "--verify", "-q", it).exitCode == 0 } ?: return emptyMap()
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
        !isJvmTestSource(relative) && relative.substringAfterLast('.', "") in API_SOURCE_EXTENSIONS

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

    private fun signatureOf(line: String): String = line
        .substringBefore('{')
        .substringBefore('=')
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun isPublicDeclaration(line: String): Boolean {
        if (line.contains("private") || line.contains("protected")) return false

        val indent = line.takeWhile { it == ' ' }.length

        if (PARAMETER.matches(line)) return indent <= PARAMETER_INDENT

        val declaration = DECLARATION.containsMatchIn(line) || TYPED_MEMBER.containsMatchIn(line)
        if (!declaration) return false

        if (indent <= MEMBER_INDENT) return true

        return EXPLICIT_MODIFIER.containsMatchIn(line)
    }

    private fun resolveMergeBase(): String? = candidateBranches()
        .flatMap { listOf("origin/$it", it) }
        .firstNotNullOfOrNull { ref ->
            run("merge-base", HEAD, ref).takeIf { it.exitCode == 0 }?.stdout?.trim()?.ifEmpty { null }
        }

    private fun candidateBranches(): List<String> =
        (listOf(baseBranch) + FALLBACK_BRANCHES).distinct().filter { it.isNotBlank() }

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
            "diff", "-U0", "--no-renames", "--no-prefix", "--no-color", "--no-ext-diff", "--no-textconv",
        )
        private const val HEAD = "HEAD"
        private const val NUL = '\u0000'
        private const val PATHSPEC_CHUNK_LENGTH = 16_000
        private const val SPACE = 0x20
        private const val DELETE = 0x7f
        private const val DEV_NULL = "/dev/null"
        private const val HEADER_PREFIX_LENGTH = 4
        private val GIT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(90).toInt()
        private val FALLBACK_BRANCHES = listOf("develop", "main", "master")

        private const val MEMBER_INDENT = 4

        private const val PARAMETER_INDENT = 8

        private val EXPLICIT_MODIFIER = Regex("""\b(public|internal|open|abstract|sealed|override|const|lateinit)\b""")

        private val DECLARATION = Regex(
            """^\s*(?:@\w+(?:\([^)]*\))?\s*)*""" +
                """(?:public\s+|internal\s+|open\s+|abstract\s+|sealed\s+|final\s+|override\s+|""" +
                """data\s+|value\s+|annotation\s+|enum\s+|inline\s+|suspend\s+|expect\s+|actual\s+|""" +
                """lateinit\s+|const\s+|external\s+|operator\s+|infix\s+|tailrec\s+|static\s+)*""" +
                """(?:fun|def|val|var|class|interface|object|trait|typealias|constructor|record)\b"""
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
): Boolean {
    if (isProjectDocumentation(path)) return false
    val fileName = path.substringAfterLast('/').substringAfterLast('\\')
    return includeAllFiles ||
        path.substringAfterLast('.', "").lowercase() in extensions ||
        fileName in names
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
