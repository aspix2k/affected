package com.aspix2k.affected.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainTest {

    @Test
    fun `the command, the base branch and the switches are read`() {
        val parsed =
            checkNotNull(parse(listOf("run", "--base", "main", "--dir", "/tmp/x", "--dependents", "--fail-fast")))

        assertEquals("run", parsed.command)
        assertEquals("main", parsed.baseBranch)
        assertEquals(File("/tmp/x").absoluteFile, parsed.directory)
        assertTrue(parsed.testDependents)
        assertTrue(parsed.stopAfterFirstFailure)
        assertEquals(false, parsed.checkConsumers)
    }

    @Test
    fun `a missing command, base branch or value and an unknown option are refused`() {
        assertNull(parse(emptyList()))
        assertNull(parse(listOf("check", "--base", "main")))
        assertNull(parse(listOf("plan")))
        assertNull(parse(listOf("plan", "--base")))
        assertNull(parse(listOf("plan", "--base", " ")))
        assertNull(parse(listOf("plan", "--base", "main", "--dir")))
        assertNull(parse(listOf("plan", "--base", "main", "--verbose")))
        assertNull(parse(listOf("audit", "--base", "main", "--fail-fast")))
        assertNull(parse(listOf("run", "--base", "main", "--break", "index.js")))
        assertNull(parse(listOf("audit", "--base", "main", "--sample", "0")))
        assertNull(parse(listOf("audit", "--base", "main", "--learn")))
    }

    @Test
    fun `wrong usage prints the help and exits with the usage code`() {
        val (code, _, errors) = run(emptyList())

        assertEquals(EXIT_USAGE, code)
        assertTrue(errors.startsWith("Usage: affected"))
    }

    @Test
    fun `a directory outside git is blocked with an explanation instead of passing`() {
        val directory = createTempDirectory("cli-plain").toFile()

        val (code, output, errors) = run(listOf("run", "--base", "main", "--dir", directory.path))

        assertEquals(EXIT_BLOCKED, code)
        assertTrue("Nothing to run." in output)
        assertTrue("not a usable git repository" in errors)
    }

    @Test
    fun `plan prints the checks a change needs and exits with success`() {
        val root = repository()
        File(root, "index.js").appendText("// changed\n")

        val (code, output, errors) = run(listOf("plan", "--base", "main", "--dir", root.path))

        assertEquals(EXIT_PASSED, code, errors)
        assertTrue("Build systems: NODE (1)" in output, output)
        assertTrue("Changed files against main: 1" in output, output)
        assertTrue(Regex("NODE in \\.: \\S*:test").containsMatchIn(output), output)
    }

    @Test
    fun `a branch without changes has nothing to run and passes`() {
        val (code, output, _) = run(listOf("run", "--base", "main", "--dir", repository().path))

        assertEquals(EXIT_PASSED, code)
        assertTrue("Nothing to run." in output)
    }

    @Test
    fun `a base branch that cannot be compared with blocks even when a plan exists`() {
        val root = repository()
        File(root, "index.js").appendText("// changed\n")

        val (code, _, errors) = run(listOf("run", "--base", "release", "--dir", root.path))

        assertEquals(EXIT_BLOCKED, code)
        assertTrue("cannot compare with 'release'" in errors, errors)
    }

    @Test
    fun `a changed source that no check owns is listed and blocks`() {
        val root = repository()
        File(root, "tools").mkdirs()
        File(root, "pom.xml").writeText("<project>")
        git(root, "add", ".")

        val (code, _, errors) = run(listOf("plan", "--base", "main", "--dir", root.path))

        assertEquals(EXIT_BLOCKED, code)
        assertTrue(errors.isNotBlank())
    }

    @Test
    fun `audit reports a test that fails only in the full run`() {
        val root = repository("first.test.js" to PASSING_TEST, "second.test.js" to FAILING_TEST)
        File(root, "first.test.js").appendText("// changed\n")

        val (code, output, errors) = run(listOf("audit", "--base", "main", "--dir", root.path))

        assertEquals(EXIT_MISSED, code, output + errors)
        assertTrue("Failed only in the full run:\n  NODE in ." in output, output)
    }

    @Test
    fun `audit runs every test of a branch that plans nothing`() {
        val root = repository("first.test.js" to FAILING_TEST)

        val (code, output, errors) = run(listOf("audit", "--base", "main", "--dir", root.path))

        assertEquals(EXIT_MISSED, code, output + errors)
        assertTrue("the planned checks ran 0 tasks, the full run 1" in output, output)
    }

    @Test
    fun `audit passes when the full run agrees with the planned checks`() {
        val root = repository("first.test.js" to PASSING_TEST, "second.test.js" to PASSING_TEST)
        File(root, "first.test.js").appendText("// changed\n")

        val (code, output, errors) = run(listOf("audit", "--base", "main", "--dir", root.path))

        assertEquals(EXIT_PASSED, code, output + errors)
        assertTrue("The full run passed." in output, output)
    }

    @Test
    fun `a broken file that a test reads from outside its package is reported as missed and restored`() {
        val root = nestedRepository()

        val (code, output, errors) =
            run(listOf("audit", "--base", "main", "--dir", root.path, "--break", "shared.txt"))

        assertEquals(EXIT_MISSED, code, output + errors)
        assertTrue("MISSED    shared.txt" in output, output)
        assertEquals("shared\n", File(root, "shared.txt").readText())
    }

    @Test
    fun `a missed file that was learned is caught by the next audit`() {
        val root = nestedRepository()
        val audit = listOf("audit", "--base", "main", "--dir", root.path, "--break", "shared.txt")

        val (learned, output, errors) = run(audit + "--learn")
        git(root, "add", "-A")
        git(root, "-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-m", "learn")
        val (code, verdicts, _) = run(audit)

        assertEquals(EXIT_MISSED, learned, output + errors)
        assertTrue("shared.txt is needed by NODE . in web" in output, output)
        assertEquals(EXIT_PASSED, code, verdicts)
        assertTrue("caught    shared.txt" in verdicts, verdicts)
    }

    @Test
    fun `a broken file inside the package is caught or goes unnoticed by every test`() {
        val root = nestedRepository()

        val (code, output, errors) = run(
            listOf("audit", "--base", "main", "--dir", root.path) +
                listOf("--break", "web/index.js", "--break", "web/notes.txt"),
        )

        assertEquals(EXIT_PASSED, code, output + errors)
        assertTrue("caught    web/index.js" in output, output)
        assertTrue("unnoticed web/notes.txt" in output, output)
        assertTrue("broke 2 files, the planned checks missed 0" in output, output)
    }

    @Test
    fun `breaking files is refused in a working tree with local changes`() {
        val root = nestedRepository()
        File(root, "shared.txt").appendText("local\n")

        val (code, _, errors) = run(listOf("audit", "--base", "main", "--dir", root.path, "--sample", "1"))

        assertEquals(EXIT_USAGE, code)
        assertTrue("without local changes" in errors, errors)
    }

    @Test
    fun `a file that git does not track is never broken`() {
        val root = nestedRepository()
        val head = File(root, ".git/HEAD").readText()

        val (code, _, errors) = run(listOf("audit", "--base", "main", "--dir", root.path, "--break", ".git/HEAD"))

        assertEquals(EXIT_USAGE, code)
        assertTrue("not a file that git tracks" in errors, errors)
        assertEquals(head, File(root, ".git/HEAD").readText())
    }

    private fun nestedRepository(): File = committed(
        "shared.txt" to "shared\n",
        "web/package.json" to """{"name":"web","version":"1.0.0","scripts":{"test":"node --test"}}""",
        "web/index.js" to "module.exports = 1\n",
        "web/notes.txt" to "notes\n",
        "web/first.test.js" to """
            const assert = require('node:assert')
            const fs = require('node:fs')
            require('node:test')('reads', () => {
                assert.equal(require('./index.js'), 1)
                assert.equal(fs.readFileSync(__dirname + '/../shared.txt', 'utf8'), 'shared\n')
            })
        """.trimIndent(),
    )

    private fun repository(vararg files: Pair<String, String>): File = committed(
        "package.json" to """{"name":"demo","version":"1.0.0","scripts":{"test":"node --test"}}""",
        "index.js" to "module.exports = 1\n",
        *files,
    )

    private fun committed(vararg files: Pair<String, String>): File {
        val root = createTempDirectory("cli-repository").toFile().canonicalFile
        files.forEach { (name, text) -> File(root, name).apply { parentFile.mkdirs() }.writeText(text) }
        git(root, "init", "--quiet", "--initial-branch=main")
        git(root, "add", ".")
        git(root, "-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-m", "init")
        git(root, "checkout", "--quiet", "-b", "feature")
        return root
    }

    private fun git(root: File, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git") + arguments).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }

    private fun run(arguments: List<String>): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = execute(arguments, PrintStream(out), PrintStream(err), createTempDirectory("cli-cache"))
        return Triple(code, out.toString(), err.toString())
    }

    private companion object {
        const val PASSING_TEST = "require('node:test')('passes', () => {})\n"
        const val FAILING_TEST = "require('node:test')('fails', () => { throw new Error('broken') })\n"
    }
}
