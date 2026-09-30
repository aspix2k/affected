package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliKotlinToolchainModuleConformanceTest {

    @Test
    fun `kotlin toolchain runs only the selected module tests`() = fixture("kotlin-toolchain-multi") { root ->
        val modules = requireNotNull(kotlinToolchainModules(root))
        val app = modules.single { it.executionId == "app" }
        val command = kotlinToolchainCommands(root, listOf("${app.executionId}:${app.testTask}")).single()
        assertEquals(listOf(kotlinToolchainWrapper(root), "test", "-m", "app"), command.arguments)
        val text = execute(root, command.arguments)
        assertContains(text, "AppTest")
        assertFalse("LibTest" in text)
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
