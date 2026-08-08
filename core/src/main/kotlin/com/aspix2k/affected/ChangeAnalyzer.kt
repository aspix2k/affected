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

    private fun changedFiles(base: String?): List<File> {
        val paths = LinkedHashSet<String>()
        if (base != null) paths += git("diff", "--name-only", "--diff-filter=d", base)
        paths += git("diff", "--name-only", "--diff-filter=d", "HEAD")
        paths += git("ls-files", "--others", "--exclude-standard")

        return paths
            .filter { path -> path.substringAfterLast('.', "") in sourceExtensions }
            .map { File(projectDir, it) }
            .filter { it.isFile }
    }

    private fun apiTouched(file: File, base: String?): Boolean {
        val relative = file.relativeTo(projectDir).invariantSeparatorsPath
        if (TEST_SOURCE_MARKERS.any { relative.contains(it) }) return false
        if (!relative.endsWith(".kt") && !relative.endsWith(".java")) return false

        val diff = when {
            base != null -> git("diff", "-U0", base, "--", relative)
            else -> emptyList()
        }.ifEmpty { git("diff", "-U0", "HEAD", "--", relative) }

        if (diff.isEmpty()) {
            return file.useLines { lines -> lines.any(::isPublicDeclaration) }
        }

        return diff.any { line ->
            if (!line.startsWith("+") && !line.startsWith("-")) return@any false
            if (line.startsWith("+++") || line.startsWith("---")) return@any false
            isPublicDeclaration(line.drop(1))
        }
    }

    private fun isPublicDeclaration(line: String): Boolean {
        if (line.contains("private") || line.contains("protected")) return false
        if (!DECLARATION.containsMatchIn(line)) return false

        val indent = line.takeWhile { it == ' ' }.length
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

        private val TEST_SOURCE_MARKERS = listOf("/src/test", "/src/androidTest")
        private const val GIT_TIMEOUT_SECONDS = 90L
        private val FALLBACK_BRANCHES = listOf("develop", "main", "master")

        private const val MEMBER_INDENT = 4

        private val EXPLICIT_MODIFIER = Regex("""\b(public|internal|open|abstract|sealed|override|const|lateinit)\b""")

        private val DECLARATION = Regex(
            """^\s*(?:@\w+(?:\([^)]*\))?\s*)*""" +
                """(?:public\s+|internal\s+|open\s+|abstract\s+|sealed\s+|final\s+|override\s+|""" +
                """data\s+|value\s+|annotation\s+|enum\s+|inline\s+|suspend\s+|expect\s+|actual\s+|""" +
                """lateinit\s+|const\s+|external\s+|operator\s+|infix\s+|tailrec\s+|static\s+)*""" +
                """(?:fun|val|var|class|interface|object|typealias|constructor|record)\b"""
        )
    }
}
