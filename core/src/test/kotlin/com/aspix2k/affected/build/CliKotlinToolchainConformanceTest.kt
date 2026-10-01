package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliKotlinToolchainConformanceTest {

    @Test
    fun `kotlin toolchain runs the project test command for the affected root`() = fixture("kotlin-toolchain") { root ->
        val modules = listOf(kotlinToolchainRootModule(root))
        val command = kotlinToolchainCommands(root, modules.map { "${it.executionId}:${it.testTask}" }).single()
        assertEquals(listOf(kotlinToolchainWrapper(root), "test"), command.arguments)
        val text = execute(root, command.arguments)
        assertContains(text, "AlphaTest")
    }

    private fun fixture(name: String, block: (File) -> Unit) {
        assumeTrue(System.getProperty("affected.cliConformance") == "true")
        val source = File(fixtureRoot(), name)
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        OwnedSandbox.use("affected-cli-$name") { sandbox ->
            assertTrue(source.copyRecursively(sandbox.root, overwrite = true), "Could not copy $source")
            File(sandbox.root, "kotlin").setExecutable(true)
            block(sandbox.root)
        }
    }

    private fun fixtureRoot(): File = CliConformanceRepository.configured.fixturesRoot()

    private fun execute(directory: File, arguments: List<String>): String =
        NativeProcessRunner.execute(
            arguments,
            directory,
            COMMAND_TIMEOUT_SECONDS,
            configure = { kotlinToolchainNativeEnvironment(environment()) },
        )

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 360L
    }
}
