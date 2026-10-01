package com.aspix2k.affected.build

import com.aspix2k.affected.build.node.nodeCommands
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliNodeRunnersConformanceTest {

    @Test
    fun `node test runs exact changed test files and preserves full fallback`() = fixture("node") { root ->
        val selected = File(root, "packages/gamma/gamma-selected.marker")
        val full = File(root, "packages/gamma/gamma-full.marker")

        assertEquals(
            listOf("npm", "test", "--workspace", "@affected/gamma", "--", "gamma.test.js"),
            executeRelated(root, "@affected/gamma", "gamma.test.js"),
        )
        assertTrue(selected.delete())
        assertFalse(full.exists())

        assertEquals(
            listOf("npm", "test", "--workspace", "@affected/gamma"),
            executeRelated(root, "@affected/gamma", "gamma.js"),
        )
        assertTrue(selected.isFile)
        assertTrue(full.isFile)
    }

    @Test
    fun `Bun runs exact changed test files and preserves full fallback`() = fixture("bun") { root ->
        val selected = File(root, "packages/delta/delta-selected.marker")
        val full = File(root, "packages/delta/delta-full.marker")

        assertEquals(
            listOf("bun", "--filter", "@affected/delta", "test", "./delta.test.ts"),
            executeRelated(root, "@affected/delta", "delta.test.ts"),
        )
        assertTrue(selected.delete())
        assertFalse(full.exists())

        assertEquals(
            listOf("bun", "--filter", "@affected/delta", "test"),
            executeRelated(root, "@affected/delta", "delta.ts"),
        )
        assertTrue(selected.isFile)
        assertTrue(full.isFile)
    }

    private fun executeRelated(root: File, packageName: String, fileName: String): List<String> {
        val file = File(root, "packages/${packageName.substringAfterLast('/')}/$fileName")
        val command = nodeCommands(
            root.path,
            listOf("$packageName:test"),
            BuildChanges(listOf(file.path), setOf(file.path), comparedToBase = true),
        ).single()
        execute(root, command.arguments)
        return command.arguments
    }

    private fun fixture(name: String, block: (File) -> Unit) {
        assumeTrue(System.getProperty("affected.cliConformance") == "true")
        val source = File(fixtureRoot(), name)
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        val target = createTempDirectory("affected-cli-$name").toFile()
        try {
            assertTrue(source.copyRecursively(target, overwrite = true), "Could not copy $source")
            block(target)
        } finally {
            target.deleteRecursively()
        }
    }

    private fun fixtureRoot(): File = CliConformanceRepository.configured.fixturesRoot()

    private fun execute(directory: File, arguments: List<String>): String {
        val output = File.createTempFile("affected-cli-output", ".log")
        try {
            val process = ProcessBuilder(arguments)
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .start()
            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            val text = output.readText()
            assertTrue(completed, "Timed out: ${arguments.joinToString(" ")}\n$text")
            assertTrue(process.exitValue() == 0, "Failed: ${arguments.joinToString(" ")}\n$text")
            return text
        } finally {
            output.delete()
        }
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 180L
    }
}
