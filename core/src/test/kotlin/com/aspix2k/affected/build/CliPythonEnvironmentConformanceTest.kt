package com.aspix2k.affected.build

import com.aspix2k.affected.build.python.PythonProjects
import com.aspix2k.affected.build.python.pythonCommands
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliPythonEnvironmentConformanceTest {

    @Test
    fun `uv runs exact pytest files inside the locked environment`() = fixture("uv-pytest") { root ->
        val modules = PythonProjects.parse(root).filter(BuildModule::hasTests)
        val module = modules.single()
        val task = listOf("${module.executionId}:test")
        val adapter = Path.of(requireNotNull(System.getProperty("affected.test.pytestAdapter")))

        val full = pythonCommands(root.path, task, modules).single().arguments
        assertEquals(listOf("uv", "run", "--locked", "python", "-m", "pytest"), full.take(6))
        assertContains(execute(root, full).output, "2 passed")

        val alpha = File(root, "alpha.py")
        val exact = pythonCommands(
            root.path,
            task,
            modules,
            BuildChanges(listOf(alpha.path), setOf(alpha.path), comparedToBase = true),
            adapter,
        ).single().arguments
        val selected = execute(root, exact).output
        assertContains(selected, "Affected pytest: exact (1 test file)")
        assertContains(selected, "1 passed")
        assertTrue(File(root, ".venv").isDirectory)

        val pyproject = File(root, "pyproject.toml")
        pyproject.writeText(pyproject.readText().replace("0.1.0", "0.1.1"))
        val stale = execute(root, pythonCommands(root.path, task, modules).single().arguments)
        assertFalse(stale.passed)
        assertContains(stale.output, "needs to be updated")
        assertFalse(stale.output.contains("passed"))
    }

    @Test
    fun `a uv workspace member runs inside the workspace environment`() = fixture("uv-workspace") { workspace ->
        File(workspace, ".git").mkdirs()
        val member = File(workspace, "packages/app")
        val modules = PythonProjects.parse(member).filter(BuildModule::hasTests)
        val task = listOf("${modules.single().executionId}:test")

        val command = pythonCommands(member.path, task, modules).single().arguments
        assertEquals(listOf("uv", "run", "--locked", "python", "-m", "unittest"), command.take(6))
        val result = execute(member, command)
        assertTrue(result.passed, result.output)
        assertContains(result.output, "Ran 1 test")
        assertTrue(File(workspace, ".venv").isDirectory)
        assertFalse(File(member, ".venv").exists())
    }

    private fun fixture(name: String, block: (File) -> Unit) {
        assumeTrue(System.getProperty("affected.cliConformance") == "true")
        val source = CliConformanceRepository.configured.fixturesRoot().resolve(name)
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        val target = createTempDirectory("affected-cli-$name").toFile()
        try {
            assertTrue(source.copyRecursively(target, overwrite = true), "Could not copy $source")
            block(target)
        } finally {
            target.deleteRecursively()
        }
    }

    private fun execute(directory: File, arguments: List<String>): Execution {
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
            return Execution(process.exitValue(), text)
        } finally {
            output.delete()
        }
    }

    private data class Execution(val exitCode: Int, val output: String) {
        val passed: Boolean get() = exitCode == 0
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 180L
    }
}
