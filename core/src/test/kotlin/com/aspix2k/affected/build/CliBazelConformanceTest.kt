package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
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
        OwnedSandbox.use("affected-cli-$name") { sandbox ->
            val target = sandbox.root
            assertTrue(source.copyRecursively(target, overwrite = true), "Could not copy $source")
            File(target, "alpha_test.sh").setExecutable(true)
            block(target)
        }
    }

    private fun fixtureRoot(): File = CliConformanceRepository.configured.fixturesRoot()

    private fun execute(directory: File, arguments: List<String>): String =
        BazelNativeRunner.execute(directory, arguments, COMMAND_TIMEOUT_SECONDS)

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 360L
    }
}
