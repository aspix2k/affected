package com.aspix2k.affected.cli

import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObservedAuditTest {

    @Test
    fun `a named file that observation also finds is counted once`() {
        assumeTrue(tracerOnPath() != null && onPath("npm"))
        val root = repository(
            "web/package.json" to MANIFEST,
            "web/reads.test.js" to """
                const fs = require('node:fs')
                require('node:test')('reads', () => {
                    require('node:assert/strict').equal(fs.readFileSync('../shared.txt', 'utf8'), 'shared\n')
                })
            """.trimIndent(),
        )

        val (code, output) = audit(root, "--break", "shared.txt")

        assertEquals(EXIT_MISSED, code, output)
        assertTrue("broke 1 files" in output, output)
        assertEquals("shared\n", File(root, "shared.txt").readText())
    }

    @Test
    fun `Go tests are observed and checked even with a warm result cache`() {
        assumeTrue(tracerOnPath() != null && onPath("go"))
        val root = repository(
            "web/go.mod" to "module example.invalid/web\n\ngo 1.21\n",
            "web/reads_test.go" to """
                package web
                import ("os"; "testing")
                func TestReads(t *testing.T) {
                    data, err := os.ReadFile("../shared.txt")
                    if err != nil || string(data) != "shared\n" { t.Fatalf("read: %q %v", data, err) }
                }
            """.trimIndent(),
        )
        command(File(root, "web"), "go", "test", "./...")
        assertTrue("(cached)" in command(File(root, "web"), "go", "test", "./..."))

        val (code, output) = audit(root)

        assertEquals(EXIT_MISSED, code, output)
        assertTrue("MISSED    shared.txt" in output, output)
        assertEquals("shared\n", File(root, "shared.txt").readText())
        assertTrue(command(root, "git", "status", "--porcelain").isBlank())
    }

    @Test
    fun `a test that changes directory still reveals its shared input`() {
        assumeTrue(tracerOnPath() != null && onPath("npm"))
        val root = repository(
            "web/package.json" to MANIFEST,
            "web/nested/keep.txt" to "directory\n",
            "web/reads.test.js" to """
                const fs = require('node:fs')
                process.chdir(__dirname + '/nested')
                require('node:test')('reads', () => {
                    require('node:assert/strict').equal(fs.readFileSync('../../shared.txt', 'utf8'), 'shared\n')
                })
            """.trimIndent(),
        )

        val (code, output) = audit(root)

        assertEquals(EXIT_MISSED, code, output)
        assertTrue("MISSED    shared.txt" in output, output)
        assertEquals("shared\n", File(root, "shared.txt").readText())
    }

    @Test
    fun `changes made only during observation stop an audit with no candidates`() {
        assumeTrue(tracerOnPath() != null && onPath("npm"))
        val counter = createTempDirectory("observed-runs").resolve("count").toFile()
        val root = repository(
            "web/package.json" to MANIFEST,
            "web/reads.test.js" to """
                const fs = require('node:fs')
                const counter = '${counter.invariantSeparatorsPath}'
                const runs = fs.existsSync(counter) ? Number(fs.readFileSync(counter)) : 0
                fs.writeFileSync(counter, String(runs + 1))
                if (runs > 0) fs.writeFileSync(__dirname + '/../shared.txt', 'changed\n')
                require('node:test')('passes', () => {})
            """.trimIndent(),
        )

        val (code, output) = audit(root)

        assertEquals(EXIT_FAILED, code, output)
        assertTrue("changed files" in output, output)
        assertTrue("broke 0 files" !in output, output)
    }

    private fun repository(vararg files: Pair<String, String>): File {
        val root = createTempDirectory("observed-repository").toFile().canonicalFile
        (listOf("shared.txt" to "shared\n") + files).forEach { (path, text) ->
            File(root, path).apply { parentFile.mkdirs() }.writeText(text)
        }
        command(root, "git", "init", "--quiet", "--initial-branch=main")
        command(root, "git", "add", ".")
        command(
            root, "git", "-c", "user.name=test", "-c", "user.email=test@example.invalid",
            "commit", "--quiet", "-m", "fixture",
        )
        return root
    }

    private fun command(root: File, vararg arguments: String): String {
        val process = ProcessBuilder(arguments.toList()).directory(root).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), arguments.joinToString(" "))
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), output)
            return output
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly)
            process.destroyForcibly()
        }
    }

    private fun audit(root: File, vararg options: String): Pair<Int, String> {
        val output = ByteArrayOutputStream()
        val stream = PrintStream(output)
        val code = execute(
            listOf("audit", "--base", "main", "--dir", root.path, "--observe") + options,
            stream, stream, createTempDirectory("observed-cache"),
        )
        return code to output.toString()
    }

    private fun onPath(name: String): Boolean = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .any { File(it, name).canExecute() }

    private companion object {
        const val MANIFEST = """{"name":"web","version":"1.0.0","scripts":{"test":"node --test"}}"""
    }
}
