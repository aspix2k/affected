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

    private fun run(arguments: List<String>): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = execute(arguments, PrintStream(out), PrintStream(err), createTempDirectory("cli-cache"))
        return Triple(code, out.toString(), err.toString())
    }
}
