package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliAntConformanceTest {

    @Test
    fun `ant runs the project test command for the affected root`() = fixture("ant") { root ->
        val modules = listOf(antRootModule(root))
        val command = antCommands(modules.map { "${it.executionId}:${it.testTask}" }).single()
        assertEquals(listOf("ant", "test"), command.arguments)
        execute(root, command.arguments)
        assertEquals("alpha", File(root, "affected-alpha.marker").readText())
    }

    private fun fixture(name: String, block: (File) -> Unit) {
        assumeTrue(System.getProperty("affected.cliConformance") == "true")
        val source = File(fixtureRoot(), name)
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        OwnedSandbox.use("affected-cli-$name") { sandbox ->
            assertTrue(source.copyRecursively(sandbox.root, overwrite = true), "Could not copy $source")
            block(sandbox.root)
        }
    }

    private fun fixtureRoot(): File = CliConformanceRepository.configured.fixturesRoot()

    private fun execute(directory: File, arguments: List<String>) {
        NativeProcessRunner.execute(arguments, directory, COMMAND_TIMEOUT_SECONDS)
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 180L
    }
}
