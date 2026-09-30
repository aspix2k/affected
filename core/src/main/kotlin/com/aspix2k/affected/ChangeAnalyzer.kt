package com.aspix2k.affected

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.TimeUnit

class ChangeAnalyzer(
    internal val projectDir: File,
    private val baseBranch: String,
    private val sourceExtensions: Set<String> = DEFAULT_EXTENSIONS,
    private val includeAllFiles: Boolean = false,
) {

    private var sourceFileNames: Set<String> = emptySet()

    internal constructor(
        projectDir: File,
        baseBranch: String,
        sourceExtensions: Set<String>,
        sourceFileNames: Set<String>,
        includeAllFiles: Boolean = false,
    ) : this(projectDir, baseBranch, sourceExtensions, includeAllFiles) {
        this.sourceFileNames = sourceFileNames
    }

    class GitFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val mergeBase: String? by lazy(::resolveMergeBase)

    fun isUsable(): Boolean = projectDir.isDirectory && run("rev-parse", "--git-dir").exitCode == 0

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
        val diffs = diffByFile()
        return files.filterTo(HashSet()) { apiTouched(it, diffs) }
    }

    internal fun keepSources(paths: Collection<String>): List<File> {
        return paths
            .filter { path ->
                isCollectedSource(path, includeAllFiles, sourceExtensions, sourceFileNames)
            }
            .map { File(projectDir, it) }
            .distinct()
    }

    private fun diffByFile(): Map<String, List<String>> {
        val ref = mergeBase ?: HEAD.takeIf { run("rev-parse", "--verify", "-q", it).exitCode == 0 } ?: return emptyMap()
        val diffs = HashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        var oldPath = ""
        var inHeader = false
        for (line in git("diff", "-U0", "--no-renames", "--no-prefix", ref).lines()) {
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

    private fun apiTouched(file: File, diffs: Map<String, List<String>>): Boolean {
        val relative = runCatching { file.relativeTo(projectDir).invariantSeparatorsPath }.getOrElse { return true }
        if (isTestSource(relative)) return false
        val extension = relative.substringAfterLast('.', "")
        if (extension !in API_SOURCE_EXTENSIONS) return false

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

    private fun git(vararg args: String): String {
        val output = run(*args)
        if (output.exitCode != 0) throw GitFailure("git ${args.first()} exited with ${output.exitCode}")
        return output.stdout
    }

    private fun run(vararg args: String): ProcessOutput = try {
        if (!projectDir.isDirectory) throw GitFailure("$projectDir is not a directory")
        val commandLine = GeneralCommandLine(listOf("git", "-c", "core.quotePath=false") + args)
            .withWorkDirectory(projectDir)
            .withCharset(Charsets.UTF_8)
        val output = CapturingProcessHandler(commandLine).runProcess(GIT_TIMEOUT_MILLIS)
        if (output.isTimeout || output.isCancelled) throw GitFailure("git ${args.first()} did not finish")
        output
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
        val DEFAULT_EXTENSIONS = setOf("kt", "kts", "java", "xml", "json", "pro")

        private val API_SOURCE_EXTENSIONS = setOf("kt", "java", "scala", "groovy")

        internal fun isTestSource(path: String): Boolean = isTestSource("GRADLE", path)

        internal fun isTestSource(systemId: String, path: String): Boolean {
            val normalised = path.replace('\\', '/')
            val segments = normalised.split('/')
            val name = segments.lastOrNull().orEmpty().lowercase()
            return TEST_SOURCE_MATCHERS[systemId]?.invoke(segments, name) ?: false
        }
        private const val HEAD = "HEAD"
        private const val NUL = '\u0000'
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

private typealias TestSourceMatcher = (List<String>, String) -> Boolean

private val TEST_SOURCE_MATCHERS: Map<String, TestSourceMatcher> = mapOf(
    "GRADLE" to { segments, _ -> isJvmTestSource(segments) },
    "MAVEN" to { segments, _ -> isJvmTestSource(segments) },
    "CARGO" to { segments, _ -> segments.any { it == "tests" || it == "benches" } },
    "GO" to { _, name -> name.endsWith("_test.go") },
    "NODE" to { segments, name ->
        segments.any { it in NODE_TEST_DIRECTORIES } || name.contains(".test.") || name.contains(".spec.")
    },
    "PYTHON" to { segments, name ->
        segments.any { it == "test" || it == "tests" } ||
            name.startsWith("test_") || name.endsWith("_test.py")
    },
    "COMPOSER" to { segments, _ ->
        segments.any { it.equals("tests", ignoreCase = true) || it == "test" }
    },
    "RUBY" to { segments, name ->
        segments.any { it == "test" || it == "spec" } ||
            name.endsWith("_spec.rb") || name.endsWith("_test.rb")
    },
    "SBT" to { segments, _ -> isJvmTestSource(segments) },
)

private fun isJvmTestSource(segments: List<String>): Boolean =
    segments.windowed(2).any { it[0] == "src" && it[1] == "test" } ||
        segments.any { it == "androidTest" || it == "androidUnitTest" }

private val NODE_TEST_DIRECTORIES = setOf("test", "tests", "spec", "specs", "__tests__")

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
