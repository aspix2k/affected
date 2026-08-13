package com.aspix2k.affected

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChangeAnalyzerOperationsTest {

    private fun repository(): File {
        val directory = createTempDirectory("analyzer-ops").toFile()
        run(directory, "git", "init", "-q", "-b", "main")
        run(directory, "git", "config", "user.email", "t@e.com")
        run(directory, "git", "config", "user.name", "t")
        File(directory, "Base.kt").writeText("fun base() {}\n")
        run(directory, "git", "add", "-A")
        run(directory, "git", "commit", "-qm", "base")
        return directory
    }

    private fun analyzer(directory: File) = ChangeAnalyzer(directory, "main", setOf("kt"))

    @Test
    fun `a directory without Git is not usable`() {
        val plain = createTempDirectory("no-vcs").toFile()

        assertFalse(analyzer(plain).isUsable(), "without Git there is nothing to compare with a branch")
    }

    @Test
    fun `a Git repository is usable`() {
        assertTrue(analyzer(repository()).isUsable())
    }

    @Test
    fun `comparison with the base sees only branch commits`() {
        val directory = repository()
        run(directory, "git", "checkout", "-q", "-b", "feature")
        File(directory, "Committed.kt").writeText("fun committed() {}\n")
        run(directory, "git", "add", "-A")
        run(directory, "git", "commit", "-qm", "committed")
        File(directory, "OnlyLocal.kt").writeText("fun local() {}\n")

        val againstBase = analyzer(directory).againstBase().map { it.name }

        assertEquals(listOf("Committed.kt"), againstBase, "uncommitted work is unrelated to the base comparison")
    }

    @Test
    fun `only modifications are eligible for exact selection`() {
        val directory = repository()
        run(directory, "git", "checkout", "-q", "-b", "feature")
        val modified = File(directory, "Base.kt").apply { appendText("fun changed() {}\n") }
        File(directory, "Added.kt").writeText("fun added() {}\n")

        val eligible = analyzer(directory).modifiedAgainstBase()

        assertEquals(setOf(modified), eligible)
    }

    @Test
    fun `a public declaration is found among changed files`() {
        val directory = repository()
        run(directory, "git", "checkout", "-q", "-b", "feature")
        val api = File(directory, "Api.kt").apply { writeText("fun added(): Int = 1\n") }
        val body = File(directory, "Base.kt").apply { appendText("// only a comment\n") }

        val touched = analyzer(directory).apiTouchedAmong(listOf(api, body))

        assertTrue(api in touched, "a new function is an API change")
        assertFalse(body in touched, "a comment is not")
    }

    @Test
    fun `comparison with the base is empty when no base exists`() {
        val directory = createTempDirectory("no-base").toFile()
        run(directory, "git", "init", "-q", "-b", "solo")
        run(directory, "git", "config", "user.email", "t@e.com")
        run(directory, "git", "config", "user.name", "t")
        File(directory, "A.kt").writeText("fun a() {}\n")
        run(directory, "git", "add", "-A")
        run(directory, "git", "commit", "-qm", "a")

        val analyzer = ChangeAnalyzer(directory, "nonexistent", setOf("kt"))

        assertEquals(
            emptyList(),
            analyzer.againstBase(),
            "without a configured or fallback branch there is no comparison",
        )
    }

    private fun run(directory: File, vararg args: String) {
        ProcessBuilder(*args).directory(directory).redirectErrorStream(true).start().waitFor()
    }
}
