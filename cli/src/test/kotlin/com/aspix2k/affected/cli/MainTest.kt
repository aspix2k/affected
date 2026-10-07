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

    private fun repository(): File {
        val root = createTempDirectory("cli-repository").toFile().canonicalFile
        File(root, "package.json").writeText("""{"name":"demo","version":"1.0.0","scripts":{"test":"node --test"}}""")
        File(root, "index.js").writeText("module.exports = 1\n")
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
}
