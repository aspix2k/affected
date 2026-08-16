package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliBazelConformanceTest {

    @Test
    fun `bazel runs the workspace test command for the affected root`() = fixture("bazel") { root ->
        val modules = listOf(bazelRootModule(root))
        val command = bazelCommands(modules.map { "${it.executionId}:${it.testTask}" }).single()
        assertEquals(listOf("bazel", "test", "//..."), command.arguments)
        val text = execute(root, command.arguments)
        assertContains(text, "//:alpha_test")
        assertContains(text, "PASSED")
    }

    private fun fixture(name: String, block: (File) -> Unit) {
        assumeTrue(System.getProperty("affected.cliConformance") == "true")
        val source = File(fixtureRoot(), name)
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        val target = createTempDirectory("affected-cli-$name").toFile()
        try {
            assertTrue(source.copyRecursively(target, overwrite = true), "Could not copy $source")
            File(target, "alpha_test.sh").setExecutable(true)
            block(target)
        } finally {
            target.deleteRecursively()
        }
    }

    private fun fixtureRoot(): File = CliConformanceRepository.configured.fixturesRoot()

    private fun execute(directory: File, arguments: List<String>): String {
        val output = File.createTempFile("affected-cli-output", ".log")
        val bazelHome = createTempDirectory("affected-bazel-out").toFile()
        try {
            val process = bazelProcess(directory, arguments, bazelHome, output).start()
            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            val text = output.readText()
            assertTrue(completed, "Timed out: ${arguments.joinToString(" ")}\n$text")
            assertTrue(completed && process.exitValue() == 0, "Failed: ${arguments.joinToString(" ")}\n$text")
            return text
        } finally {
            bazelProcess(directory, listOf("bazel", "shutdown"), bazelHome, output).start()
                .waitFor(15, TimeUnit.SECONDS)
            bazelHome.deleteRecursively()
            output.delete()
        }
    }

    private fun bazelProcess(directory: File, arguments: List<String>, bazelHome: File, output: File): ProcessBuilder =
        ProcessBuilder(arguments)
            .directory(directory)
            .redirectErrorStream(true)
            .redirectOutput(output)
            .apply { environment()["TEST_TMPDIR"] = bazelHome.absolutePath }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 360L
    }
}
