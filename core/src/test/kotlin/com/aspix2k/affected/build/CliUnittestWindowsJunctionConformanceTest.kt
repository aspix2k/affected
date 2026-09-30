package com.aspix2k.affected.build

import com.aspix2k.affected.build.process.CliCommand
import com.aspix2k.affected.build.python.PythonProjects
import com.aspix2k.affected.build.python.pythonCommands
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
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
        OwnedSandbox.use("affected-unittest-junction") { sandbox ->
            val root = sandbox.directory("root")
            val outside = sandbox.directory("outside")
            assertTrue(source.copyRecursively(root, overwrite = true), "Could not copy $source")
            block(root, outside)
        }
    }

    private fun unittestCommand(root: File, changed: File): CliCommand {
        val module = PythonProjects.parse(root).single { it.id == "affected-unittest-alpha" }
        val adapter = CliConformanceRepository.configured
            .repositoryFile("core/src/main/python/affected_unittest.py")
            .toPath()
        val command = pythonCommands(
            root.path,
            listOf("${module.executionId}:test"),
            listOf(module),
            BuildChanges(
                files = listOf(changed.path),
                exactSelectionEligible = setOf(changed.path),
                comparedToBase = true,
            ),
            adapter,
        ).single()
        assertEquals(listOf("python", adapter.toString()), command.arguments.take(2))
        return command
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
        val result = NativeProcessRunner.run(arguments, directory, timeoutSeconds)
        assertTrue(result.completed, "Timed out: ${arguments.joinToString(" ")}\n${result.output}")
        assertTrue(result.output.length <= MAX_OUTPUT_BYTES, "Process output exceeded $MAX_OUTPUT_BYTES bytes")
        return Execution(checkNotNull(result.exitCode), result.output)
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
        const val MAX_OUTPUT_BYTES = 64 * 1024
    }
}
