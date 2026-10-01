package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliBazelPackageConformanceTest {

    @Test
    fun `bazel runs only the selected package tests`() = fixture("bazel-packages") { root ->
        val packages = requireNotNull(bazelPackages(root))
        val alpha = packages.single { it.executionId == "alpha" }
        val command = bazelCommands(listOf("${alpha.executionId}:${alpha.testTask}")).single()
        assertEquals(listOf("bazel", "test", "//alpha:all"), command.arguments)
        val text = execute(root, command.arguments)
        assertContains(text, "//alpha:alpha_test")
        assertContains(text, "PASSED")
        assertFalse("//beta:beta_test" in text)
    }

    private fun fixture(name: String, block: (File) -> Unit) {
        assumeTrue(System.getProperty("affected.cliConformance") == "true")
        val source = File(fixtureRoot(), name)
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        OwnedSandbox.use("affected-cli-$name") { sandbox ->
            val target = sandbox.root
            assertTrue(source.copyRecursively(target, overwrite = true), "Could not copy $source")
            File(target, "alpha/alpha_test.sh").setExecutable(true)
            File(target, "beta/beta_test.sh").setExecutable(true)
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
