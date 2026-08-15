package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CliFlutterConformanceTest {

    @Test
    fun `flutter runs the project test command for the affected root`() = fixture("flutter") { root ->
        val module = flutterRootModule(root)
        val command = flutterCommands(listOf("${module.executionId}:${module.testTask}")).single()
        assertEquals(listOf("flutter", "test"), command.arguments)
        resolve(root)
        val text = execute(root, command.arguments)
        assertContains(text, "AlphaTest")
        assertTrue("All tests passed" in text || "1 test passed" in text, text)
    }

    @Test
    fun `flutter runs from a single first-level nested app`() = fixture("flutter", nested = true) { root ->
        val nested = assertNotNull(flutterProjectRoot(root))
        assertEquals(File(root, "app").canonicalFile, nested.canonicalFile)
        val module = flutterRootModule(nested)
        val command = flutterCommands(listOf("${module.executionId}:${module.testTask}")).single()
        resolve(nested)
        val text = execute(nested, command.arguments)
        assertContains(text, "AlphaTest")
        assertTrue("All tests passed" in text || "1 test passed" in text, text)
    }

    private fun fixture(name: String, nested: Boolean = false, block: (File) -> Unit) {
        assumeTrue(System.getProperty("affected.cliConformance") == "true")
        val source = File(fixtureRoot(), name)
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        val target = createTempDirectory("affected-cli-$name").toFile()
        try {
            val destination = if (nested) File(target, "app") else target
            assertTrue(source.copyRecursively(destination, overwrite = true), "Could not copy $source")
            block(target)
        } finally {
            target.deleteRecursively()
        }
    }

    private fun fixtureRoot(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "conformance/cli-fixtures") }
        .firstOrNull(File::isDirectory)
        ?: File(System.getProperty("user.dir"), "conformance/cli-fixtures")

    private fun resolve(directory: File) {
        execute(directory, listOf("flutter", "pub", "get"))
    }

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
            assertTrue(completed && process.exitValue() == 0, "Failed: ${arguments.joinToString(" ")}\n$text")
            return text
        } finally {
            output.delete()
        }
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 180L
    }
}
