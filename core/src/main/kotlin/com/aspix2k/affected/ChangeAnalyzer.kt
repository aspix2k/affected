package com.aspix2k.affected

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.TimeUnit

class ChangeAnalyzer(
    private val projectDir: File,
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

    data class Changes(val files: List<File>, val apiTouched: Set<File>)

    fun collectPaths(): List<File> = changedFiles(mergeBase())

    fun collect(): Changes {
        val base = mergeBase()
        val files = changedFiles(base)
        return Changes(files, files.filter { apiTouched(it, base) }.toSet())
    }

    fun isUsable(): Boolean = git("rev-parse", "--git-dir").isNotEmpty()

    fun hasComparisonBase(): Boolean = mergeBase() != null

    fun modifiedAgainstBase(): Set<File> {
        val base = mergeBase() ?: return emptySet()
        val paths = git("diff", "--name-status", "--no-renames", base)
            .mapNotNull { line ->
                val separator = line.indexOf('\t')
                line.substring(0, separator.takeIf { it > 0 } ?: return@mapNotNull null)
                    .takeIf { it == "M" }
                    ?.let { line.substring(separator + 1) }
            }
        return keepSources(paths).toSet()
    }

    fun againstBase(): List<File> {
        val base = mergeBase() ?: return emptyList()
        return keepSources(git("diff", "--name-only", "--no-renames", base))
    }

    fun apiTouchedAmong(files: Collection<File>): Set<File> {
        val base = mergeBase()
        return files.filterTo(HashSet()) { apiTouched(it, base) }
    }

    private fun changedFiles(base: String?): List<File> {
        val paths = LinkedHashSet<String>()
        if (base != null) paths += git("diff", "--name-only", "--no-renames", base)
        paths += git("diff", "--name-only", "--no-renames", "HEAD")
        paths += git("ls-files", "--others", "--exclude-standard")

        return keepSources(paths)
    }

    private fun keepSources(paths: Collection<String>): List<File> {
        return paths
            .filter { path ->
                includeAllFiles ||
                    path.substringAfterLast('.', "").lowercase() in sourceExtensions ||
                    path.substringAfterLast('/').substringAfterLast('\\') in sourceFileNames
            }
            .map { File(projectDir, it) }
            .distinct()
    }

    private fun apiTouched(file: File, base: String?): Boolean {
        val relative = runCatching { file.relativeTo(projectDir).invariantSeparatorsPath }.getOrElse { return true }
        if (isTestSource(relative)) return false
        val extension = relative.substringAfterLast('.', "")
        if (extension != "kt" && extension != "java") return false

        val diff = when {
            base != null -> git("diff", "-U0", base, "--", relative)
            else -> emptyList()
        }.ifEmpty { git("diff", "-U0", "HEAD", "--", relative) }

        if (diff.isEmpty()) {
            if (!file.isFile) return false
            return file.useLines { lines -> lines.any(::isPublicDeclaration) }
        }

        val removed = signatures(diff, "-")
        val added = signatures(diff, "+")

        return removed != added
    }

    private fun signatures(diff: List<String>, marker: String): Set<String> = diff
        .filter { it.startsWith(marker) && !it.startsWith(marker.repeat(3)) }
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

    private fun mergeBase(): String? {
        for (branch in candidateBranches()) {
            for (ref in listOf("origin/$branch", branch)) {
                val result = git("merge-base", "HEAD", ref).firstOrNull()
                if (!result.isNullOrBlank()) return result.trim()
            }
        }
        return null
    }

    private fun candidateBranches(): List<String> =
        (listOf(baseBranch) + FALLBACK_BRANCHES).distinct().filter { it.isNotBlank() }

    private fun git(vararg args: String): List<String> = try {
        if (!projectDir.isDirectory) return emptyList()
        val commandLine = GeneralCommandLine(listOf("git") + args)
            .withWorkDirectory(projectDir)
            .withCharset(Charsets.UTF_8)
        val output = CapturingProcessHandler(commandLine).runProcess(GIT_TIMEOUT_MILLIS)
        if (output.exitCode == 0 && !output.isTimeout && !output.isCancelled) output.stdoutLines else emptyList()
    } catch (error: CancellationException) {
        throw error
    } catch (error: ProcessCanceledException) {
        throw error
    } catch (error: Exception) {
        emptyList()
    }

    companion object {
        val DEFAULT_EXTENSIONS = setOf("kt", "kts", "java", "xml", "json", "pro")

        internal fun isTestSource(path: String): Boolean = isTestSource("GRADLE", path)

        internal fun isTestSource(systemId: String, path: String): Boolean {
            val normalised = path.replace('\\', '/')
            val segments = normalised.split('/')
            val name = segments.lastOrNull().orEmpty().lowercase()
            return TEST_SOURCE_MATCHERS[systemId]?.invoke(segments, name) ?: false
        }
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
                """(?:fun|val|var|class|interface|object|typealias|constructor|record)\b"""
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
)

private fun isJvmTestSource(segments: List<String>): Boolean =
    segments.windowed(2).any { it[0] == "src" && it[1] == "test" } ||
        segments.any { it == "androidTest" || it == "androidUnitTest" }

private val NODE_TEST_DIRECTORIES = setOf("test", "tests", "spec", "specs", "__tests__")
