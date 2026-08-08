package com.aspix2k.affected

import java.io.File
import java.util.concurrent.TimeUnit

class ChangeAnalyzer(
    private val projectDir: File,
    private val baseBranch: String,
    private val sourceExtensions: Set<String> = DEFAULT_EXTENSIONS,
) {

    data class Changes(val files: List<File>, val apiTouched: Set<File>)

    fun collectPaths(): List<File> = changedFiles(mergeBase())

    fun collect(): Changes {
        val base = mergeBase()
        val files = changedFiles(base)
        return Changes(files, files.filter { apiTouched(it, base) }.toSet())
    }

    /** Whether git can answer at all; a project under another VCS cannot be diffed against a branch. */
    fun isUsable(): Boolean = git("rev-parse", "--git-dir").isNotEmpty()

    /** Files this branch changed relative to where it left the base branch. */
    fun againstBase(): List<File> {
        val base = mergeBase() ?: return emptyList()
        return keepSources(git("diff", "--name-only", "--diff-filter=d", base))
    }

    /** Which of the given files carry a public declaration among their changed lines. */
    fun apiTouchedAmong(files: Collection<File>): Set<File> {
        val base = mergeBase()
        return files.filterTo(HashSet()) { apiTouched(it, base) }
    }

    private fun changedFiles(base: String?): List<File> {
        val paths = LinkedHashSet<String>()
        if (base != null) paths += git("diff", "--name-only", "--diff-filter=d", base)
        paths += git("diff", "--name-only", "--diff-filter=d", "HEAD")
        paths += git("ls-files", "--others", "--exclude-standard")

        return keepSources(paths)
    }

    private fun keepSources(paths: Collection<String>): List<File> {
        return paths
            .filter { path -> path.substringAfterLast('.', "") in sourceExtensions }
            .map { File(projectDir, it) }
            .filter { it.isFile }
            .distinct()
    }

    private fun apiTouched(file: File, base: String?): Boolean {
        val relative = file.relativeTo(projectDir).invariantSeparatorsPath
        if (TEST_SOURCE_MARKERS.any { relative == it || relative.startsWith("$it/") || relative.contains("/$it/") }) {
            return false
        }
        if (!relative.endsWith(".kt") && !relative.endsWith(".java")) return false

        val diff = when {
            base != null -> git("diff", "-U0", base, "--", relative)
            else -> emptyList()
        }.ifEmpty { git("diff", "-U0", "HEAD", "--", relative) }

        if (diff.isEmpty()) {
            return file.useLines { lines -> lines.any(::isPublicDeclaration) }
        }

        // Comparing the declarations either side of the change, rather than
        // reacting to any changed line that looks like one. A member written on
        // a single line carries its body with it — `public int value() { return 1; }`
        // — and editing that body must not count as an API change.
        val removed = signatures(diff, "-")
        val added = signatures(diff, "+")

        return removed != added
    }

    private fun signatures(diff: List<String>, marker: String): Set<String> = diff
        .filter { it.startsWith(marker) && !it.startsWith(marker.repeat(3)) }
        .map { it.drop(1) }
        .filter(::isPublicDeclaration)
        .mapTo(HashSet(), ::signatureOf)

    /** The part a caller depends on: everything before the body or the initialiser. */
    private fun signatureOf(line: String): String = line
        .substringBefore('{')
        .substringBefore('=')
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun isPublicDeclaration(line: String): Boolean {
        if (line.contains("private") || line.contains("protected")) return false

        val indent = line.takeWhile { it == ' ' }.length

        // A parameter sitting on its own line is part of a signature, and
        // changing it breaks callers exactly as changing the first line would.
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
        val process = ProcessBuilder(listOf("git") + args)
            .directory(projectDir)
            .redirectErrorStream(false)
            .start()
        val output = process.inputStream.bufferedReader().readLines()
        process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (process.exitValue() == 0) output else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        val DEFAULT_EXTENSIONS = setOf("kt", "kts", "java", "xml", "json", "pro")

        private val TEST_SOURCE_MARKERS = listOf("src/test", "src/androidTest", "src/androidUnitTest")
        private const val GIT_TIMEOUT_SECONDS = 90L
        private val FALLBACK_BRANCHES = listOf("develop", "main", "master")

        private const val MEMBER_INDENT = 4

        // A signature is wrapped one level deeper than the member it belongs to;
        // anything further in is a value inside a body, not a parameter.
        private const val PARAMETER_INDENT = 8

        private val EXPLICIT_MODIFIER = Regex("""\b(public|internal|open|abstract|sealed|override|const|lateinit)\b""")

        private val DECLARATION = Regex(
            """^\s*(?:@\w+(?:\([^)]*\))?\s*)*""" +
                """(?:public\s+|internal\s+|open\s+|abstract\s+|sealed\s+|final\s+|override\s+|""" +
                """data\s+|value\s+|annotation\s+|enum\s+|inline\s+|suspend\s+|expect\s+|actual\s+|""" +
                """lateinit\s+|const\s+|external\s+|operator\s+|infix\s+|tailrec\s+|static\s+)*""" +
                """(?:fun|val|var|class|interface|object|typealias|constructor|record)\b"""
        )

        // Java and C-like languages name the type instead of using a keyword:
        // `public String find(int id)`, `protected List<Item> items;`. Without
        // this, a changed Java signature reads as an ordinary line and no
        // consumer is ever checked.
        private val TYPED_MEMBER = Regex(
            """^\s*(?:@\w+(?:\([^)]*\))?\s*)*""" +
                """(?:public\s+|protected\s+|static\s+|final\s+|abstract\s+|synchronized\s+|""" +
                """native\s+|default\s+|strictfp\s+|transient\s+|volatile\s+)*""" +
                """[A-Za-z_][\w.<>\[\], ?]*\s+[A-Za-z_]\w*\s*[(;=]"""
        )

        // A parameter on its own line inside a multi-line signature.
        private val PARAMETER = Regex("""^\s*(?:@\w+\s*)*[A-Za-z_]\w*\s*:\s*[\w<>\[\]?., ]+,?\s*$""")
    }
}
