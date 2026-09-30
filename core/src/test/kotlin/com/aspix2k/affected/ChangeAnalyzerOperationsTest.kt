package com.aspix2k.affected

import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.progress.ProcessCanceledException
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `committed files with non-ASCII names are found`() {
        val directory = repository()
        run(directory, "git", "checkout", "-q", "-b", "feature")
        File(directory, "Тест.kt").writeText("fun test() {}\n")
        File(directory, "日本 語.kt").writeText("fun spaced() {}\n")
        run(directory, "git", "add", "-A")
        run(directory, "git", "commit", "-qm", "unicode")

        val names = analyzer(directory).againstBase().map { it.name }.toSet()

        assertEquals(setOf("Тест.kt", "日本 語.kt"), names)
    }

    @Test
    fun `non-ASCII modifications are eligible for exact selection`() {
        val directory = repository()
        val unicode = File(directory, "Тест.kt").apply { writeText("fun test() {}\n") }
        run(directory, "git", "add", "-A")
        run(directory, "git", "commit", "-qm", "unicode")
        run(directory, "git", "checkout", "-q", "-b", "feature")
        unicode.appendText("fun more() {}\n")

        assertEquals(setOf(unicode), analyzer(directory).modifiedAgainstBase())
        assertEquals(setOf(unicode), analyzer(directory).apiTouchedAmong(listOf(unicode)))
    }

    @Test
    fun `a failing Git is reported instead of an empty change list`() {
        val directory = repository()
        run(directory, "git", "checkout", "-q", "-b", "feature")
        File(directory, "Committed.kt").writeText("fun committed() {}\n")
        File(directory, ".git/index").writeText("not an index")

        assertFailsWith<ChangeAnalyzer.GitFailure> { analyzer(directory).againstBase() }
        assertFailsWith<ChangeAnalyzer.GitFailure> { analyzer(directory).modifiedAgainstBase() }
        val files = listOf(File(directory, "A.kt"))
        assertFailsWith<ChangeAnalyzer.GitFailure> { analyzer(directory).apiTouchedAmong(files) }
    }

    @Test
    fun `an API change is detected from one diff for many files`() {
        val directory = repository()
        val spaced = File(directory, "With Space.kt").apply { writeText("fun old(): Int = 1\n") }
        run(directory, "git", "add", "-A")
        run(directory, "git", "commit", "-qm", "spaced")
        run(directory, "git", "checkout", "-q", "-b", "feature")
        spaced.writeText("fun old(): Int = 2\n")
        val renamed = File(directory, "Base.kt").apply { writeText("fun renamed() {}\n") }

        val touched = analyzer(directory).apiTouchedAmong(listOf(spaced, renamed))

        assertEquals(setOf(renamed), touched)
    }

    private class RecordingGit(val script: File, private val log: File, private val pathspecs: File) {
        fun commands(): List<String> = log.takeIf { it.isFile }?.readLines().orEmpty()

        fun requestedPaths(): List<String> = pathspecs.takeIf { it.isFile }?.readLines().orEmpty()
    }

    private fun recordingGit(): RecordingGit {
        val home = createTempDirectory("recording-git").toFile()
        val log = File(home, "commands.log")
        val pathspecs = File(home, "pathspecs.log")
        val script = File(home, "git").apply {
            writeText(
                """
                #!/bin/sh
                echo "${'$'}*" >> '${log.path}'
                after=0
                for arg in "${'$'}@"; do
                  if [ "${'$'}after" = 1 ]; then echo "${'$'}arg" >> '${pathspecs.path}'; fi
                  if [ "${'$'}arg" = "--" ]; then after=1; fi
                done
                exec git "${'$'}@"
                """.trimIndent() + "\n"
            )
            setExecutable(true)
        }
        return RecordingGit(script, log, pathspecs)
    }

    @Test
    fun `the diff is restricted to the files that need an API check`() {
        val directory = repository()
        val wanted = File(directory, "Wanted.kt").apply { writeText("fun wanted(): Int = 1\n") }
        val other = File(directory, "Other.kt").apply { writeText("fun other(): Int = 1\n") }
        File(directory, "yarn.lock").writeText("lock\n")
        run(directory, "git", "add", "-A")
        run(directory, "git", "commit", "-qm", "more")
        run(directory, "git", "checkout", "-q", "-b", "feature")
        wanted.writeText("fun wanted(): Long = 1\n")
        other.writeText("fun other(): Long = 1\n")
        File(directory, "yarn.lock").appendText("changed\n")
        val recorder = recordingGit()
        val analyzer = ChangeAnalyzer(directory, "main", setOf("kt"), false, recorder.script.path)

        val touched = analyzer.apiTouchedAmong(listOf(wanted))

        assertEquals(setOf(wanted), touched)
        assertEquals(listOf("Wanted.kt"), recorder.requestedPaths())
    }

    @Test
    fun `no diff is computed when no file needs an API check`() {
        val directory = repository()
        run(directory, "git", "checkout", "-q", "-b", "feature")
        val test = File(directory, "src/test/kotlin/SomeTest.kt").apply {
            parentFile.mkdirs()
            writeText("fun helper() {}\n")
        }
        val manifest = File(directory, "config.json").apply { writeText("{}\n") }
        val recorder = recordingGit()
        val analyzer = ChangeAnalyzer(directory, "main", setOf("kt"), false, recorder.script.path)

        val touched = analyzer.apiTouchedAmong(listOf(test, manifest))

        assertEquals(emptySet(), touched)
        assertTrue(recorder.commands().none { it.contains("diff") }, "commands: ${recorder.commands()}")
    }

    @Test
    fun `names that Git quotes are never narrowed`() {
        val directory = repository()
        val names = listOf("we\"ird.kt", "back\\slash.kt", "tab\tname.kt")
        val files = names.map { File(directory, it).apply { writeText("fun old(): Int = 1\n") } }
        run(directory, "git", "add", "-A")
        run(directory, "git", "commit", "-qm", "quoted")
        run(directory, "git", "checkout", "-q", "-b", "feature")
        files.forEach { it.writeText("private fun old(): Int = 1\n") }

        val touched = analyzer(directory).apiTouchedAmong(files)

        assertEquals(files.toSet(), touched, "a removed public declaration must not be reported as untouched")
    }

    @Test
    fun `a missing Git binary means the project is not a Git project`() {
        val directory = repository()
        val analyzer = ChangeAnalyzer(directory, "main", setOf("kt"), false, "no-such-git-binary")

        assertFalse(analyzer.isUsable())
    }

    @Test
    fun `a missing Git binary is a failure for diff operations`() {
        val analyzer = ChangeAnalyzer(repository(), "main", setOf("kt"), false, "no-such-git-binary")

        assertFailsWith<ChangeAnalyzer.GitFailure> { analyzer.modifiedAgainstBase() }
    }

    @Test
    fun `a cancelled capture propagates as cancellation and a timeout is a failure`() {
        val cancelled = ProcessOutput().apply { setCancelled() }
        val timedOut = ProcessOutput().apply { setTimeout() }

        assertFailsWith<ProcessCanceledException> { ChangeAnalyzer.ensureFinished(cancelled, "diff") }
        assertFailsWith<ChangeAnalyzer.GitFailure> { ChangeAnalyzer.ensureFinished(timedOut, "diff") }
    }

    private fun run(directory: File, vararg args: String) {
        ProcessBuilder(*args).directory(directory).redirectErrorStream(true).start().waitFor()
    }
}
