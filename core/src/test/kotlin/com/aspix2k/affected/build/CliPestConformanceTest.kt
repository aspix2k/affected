package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliPestConformanceTest {

    @Test
    fun `Pest 5 runs only the changed test files`() = fixture("pest") { root ->
        val lock = root.resolve("composer.lock")
        val locked = lock.readBytes()
        execute(root, listOf("composer", "install", "--no-interaction", "--no-progress", "--no-scripts"))
        val pestTemp = File(root, "vendor/pestphp/pest/.temp")
        assertTrue(pestTemp.mkdirs() || pestTemp.isDirectory)
        assertTrue(locked.contentEquals(lock.readBytes()))
        val alpha = ComposerPackages.parse(root).single { it.id == "affected/fixture-pest-alpha" }
        val changed = File(root, "packages/alpha/tests/AlphaTest.php")
        val command = composerCommands(
            root.path,
            listOf("${alpha.executionId}:${alpha.testTask}"),
            listOf(alpha),
            BuildChanges(
                files = listOf(changed.path),
                exactSelectionEligible = setOf(changed.path),
                comparedToBase = true,
            ),
        ).single()

        assertEquals(listOf("./packages/alpha/tests/AlphaTest.php"), command.arguments.takeLast(1))
        execute(root, command.arguments)

        assertEquals("alpha", File(root, "packages/alpha/alpha.marker").readText())
        assertEquals(listOf("first", "second"), File(root, "packages/alpha/dataset.marker").readLines())
        assertFalse(File(root, "packages/alpha/phpunit.marker").exists())
        assertFalse(File(root, "packages/beta/beta.marker").exists())
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

    private fun fixtureRoot(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "conformance/cli-fixtures") }
        .firstOrNull(File::isDirectory)
        ?: File(System.getProperty("user.dir"), "conformance/cli-fixtures")

    private fun execute(directory: File, arguments: List<String>) {
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
        } finally {
            output.delete()
        }
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 180L
    }
}
