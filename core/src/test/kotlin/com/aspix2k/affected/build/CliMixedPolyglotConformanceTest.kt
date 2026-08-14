package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliMixedPolyglotConformanceTest {

    @Test
    fun `cmake tests a mixed repository without running dotnet`() = fixture { root ->
        execute(root, listOf("cmake", "-S", ".", "-B", "build"))
        val cmake = CMakeTargets.parse(root)
        val output = cmakeCommands(root.path, cmake.map { "${it.executionId}:${it.testTask}" })
            .joinToString("\n") { execute(root, it.arguments) }

        assertContains(output, "mixed_alpha")
        assertFalse(output.contains("dotnet"), output)
    }

    @Test
    fun `dotnet tests a mixed repository without running cmake`() = fixture { root ->
        val tested = DotnetProjects.parse(root).filter { it.testTask == DotnetProjects.TEST }
        val output = dotnetCommands(root.path, tested.map { "${it.executionId}:${it.testTask}" })
            .joinToString("\n") { execute(root, it.arguments) }

        assertContains(output, "Passed")
        assertFalse(output.contains("cmake"), output)
        assertFalse(output.contains("ctest"), output)
    }

    private fun fixture(block: (File) -> Unit) {
        assumeTrue(System.getProperty("affected.cliConformance") == "true")
        val source = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "conformance/cli-fixtures/mixed-cmake-dotnet") }
            .firstOrNull(File::isDirectory)
            ?: File(System.getProperty("user.dir"), "conformance/cli-fixtures/mixed-cmake-dotnet")
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        val target = createTempDirectory("affected-cli-mixed").toFile()
        try {
            assertTrue(source.copyRecursively(target, overwrite = true), "Could not copy $source")
            block(target)
        } finally {
            target.deleteRecursively()
        }
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
