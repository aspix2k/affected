package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliUnittestWindowsJunctionConformanceTest {

    @Test
    fun `unittest rejects a nested package junction before importing outside tests`() = fixture { root, outside ->
        val command = unittestCommand(root, File(root, "packages/alpha/test_helpers.py"))
        val sentinel = File(outside, "nested-import.marker")
        val target = File(outside, "nested").apply { mkdirs() }
        File(target, "__init__.py").writeText("")
        File(target, "test_outside.py").writeText(outsideTest(sentinel))
        val junction = File(root, "packages/alpha/junction").toPath().toAbsolutePath()

        try {
            createJunction(junction, target.toPath().toAbsolutePath())

            val execution = execute(root, command.arguments)

            assertEquals(2, execution.exitCode, execution.output)
            assertTrue(execution.output.contains("unsafe discovery (discovery-symlink)"), execution.output)
            assertFalse(sentinel.exists(), execution.output)
            assertFalse(File(root, "packages/alpha/consumer.marker").exists(), execution.output)
            assertFalse(File(root, "packages/beta/beta.marker").exists(), execution.output)
        } finally {
            deleteJunction(junction)
        }
    }

    @Test
    fun `unittest rejects a package root junction introduced after planning`() = fixture { root, outside ->
        val selected = File(root, "packages/alpha/test_helpers.py")
        val command = unittestCommand(root, selected)
        val alpha = selected.parentFile.toPath().toAbsolutePath()
        val backup = alpha.resolveSibling("alpha-planned")
        val sentinel = File(outside, "root-import.marker")
        val target = File(outside, "alpha").apply { mkdirs() }
        File(target, "__init__.py").writeText(
            """
            from pathlib import Path

            Path('${sentinel.invariantSeparatorsPath}').write_text('unsafe', encoding='utf-8')
            """.trimIndent() + "\n",
        )
        File(target, "test_helpers.py").writeText("VALUE = 'outside'\n")

        Files.move(alpha, backup)
        try {
            createJunction(alpha, target.toPath().toAbsolutePath())

            val execution = execute(root, command.arguments)

            assertEquals(2, execution.exitCode, execution.output)
            assertTrue(execution.output.contains("invalid context (symlink)"), execution.output)
            assertFalse(sentinel.exists(), execution.output)
        } finally {
            deleteJunction(alpha)
            Files.move(backup, alpha)
        }
    }

    private fun fixture(block: (File, File) -> Unit) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assumeTrue(System.getProperty("affected.cliConformance") == "true")
        val source = CliConformanceRepository.configured.fixture("unittest")
        val root = createTempDirectory("affected-unittest-junction").toFile()
        val outside = createTempDirectory("affected-unittest-outside").toFile()
        try {
            assertTrue(source.copyRecursively(root, overwrite = true), "Could not copy $source")
            block(root, outside)
        } finally {
            assertTrue(root.deleteRecursively(), "Could not delete $root")
            assertTrue(outside.deleteRecursively(), "Could not delete $outside")
        }
    }

    private fun unittestCommand(root: File, changed: File): CliCommand {
        val module = PythonProjects.parse(root).single { it.id == "affected-unittest-alpha" }
        return pythonCommands(
            root.path,
            listOf("${module.executionId}:test"),
            listOf(module),
            BuildChanges(
                files = listOf(changed.path),
                exactSelectionEligible = setOf(changed.path),
                comparedToBase = true,
            ),
            CliConformanceRepository.configured
                .repositoryFile("core/src/main/python/affected_unittest.py")
                .toPath(),
        ).single()
    }

    private fun createJunction(junction: Path, target: Path) {
        require(junction.isAbsolute && target.isAbsolute)
        check(Files.notExists(junction, LinkOption.NOFOLLOW_LINKS)) { "Junction already exists: $junction" }
        check(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(target)) {
            "Junction target is not a readable directory: $target"
        }

        val execution = runProcess(
            junction.parent.toFile(),
            listOf("cmd.exe", "/d", "/c", "mklink", "/J", junction.toString(), target.toString()),
            JUNCTION_TIMEOUT_SECONDS,
        )

        assertEquals(0, execution.exitCode, execution.output)
        assertEquals(target.toRealPath(), junction.toRealPath())
    }

    private fun deleteJunction(junction: Path) {
        Files.deleteIfExists(junction)
        assertFalse(Files.exists(junction, LinkOption.NOFOLLOW_LINKS), "Junction still exists: $junction")
    }

    private fun execute(directory: File, arguments: List<String>): Execution =
        runProcess(directory, arguments, COMMAND_TIMEOUT_SECONDS)

    private fun runProcess(directory: File, arguments: List<String>, timeoutSeconds: Long): Execution {
        check(directory.isDirectory && directory.canRead()) { "Process directory is not readable: $directory" }
        val output = Files.createTempFile("affected-unittest-process", ".log").toFile()
        try {
            val process = ProcessBuilder(arguments)
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .start()
            val completed = try {
                process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            } catch (failure: InterruptedException) {
                val terminated = terminate(process)
                Thread.currentThread().interrupt()
                if (!terminated) {
                    throw IllegalStateException(
                        "Could not terminate after interruption: ${arguments.joinToString(" ")}",
                        failure,
                    )
                }
                throw failure
            }
            if (!completed) {
                assertTrue(terminate(process), "Could not terminate: ${arguments.joinToString(" ")}")
            }
            val text = readBounded(output)
            assertTrue(completed, "Timed out: ${arguments.joinToString(" ")}\n$text")
            return Execution(process.exitValue(), text)
        } finally {
            assertTrue(output.delete(), "Could not delete $output")
        }
    }

    private fun terminate(process: Process): Boolean {
        process.destroyForcibly()
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TERMINATION_TIMEOUT_SECONDS)
        while (process.isAlive && System.nanoTime() < deadline) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            try {
                process.waitFor(remaining, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return !process.isAlive
    }

    private fun readBounded(output: File): String {
        val bytes = output.inputStream().use { stream -> stream.readNBytes(MAX_OUTPUT_BYTES + 1) }
        assertTrue(bytes.size <= MAX_OUTPUT_BYTES, "Process output exceeded $MAX_OUTPUT_BYTES bytes")
        return String(bytes, Charset.defaultCharset())
    }

    private fun outsideTest(sentinel: File): String =
        """
        import unittest
        from pathlib import Path

        Path('${sentinel.invariantSeparatorsPath}').write_text('imported', encoding='utf-8')

        class OutsideTest(unittest.TestCase):
            def test_outside(self):
                self.fail('outside test must not run')
        """.trimIndent() + "\n"

    private data class Execution(val exitCode: Int, val output: String)

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 180L
        const val JUNCTION_TIMEOUT_SECONDS = 10L
        const val TERMINATION_TIMEOUT_SECONDS = 10L
        const val MAX_OUTPUT_BYTES = 64 * 1024
    }
}
