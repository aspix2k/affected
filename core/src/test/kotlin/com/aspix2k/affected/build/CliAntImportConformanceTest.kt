package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliAntImportConformanceTest {

    @Test
    fun `ant runs tests declared in an imported build file`() = fixture("ant-imports") { root ->
        val module = antRootModule(root)
        assertEquals("test", module.testTask)
        val command = antCommands(listOf("${module.executionId}:${module.testTask}")).single()
        assertEquals(listOf("ant", "test"), command.arguments)
        val text = execute(root, command.arguments)
        assertContains(text, "AlphaTest")
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

    private fun execute(directory: File, arguments: List<String>): String =
        NativeProcessRunner.execute(arguments, directory, COMMAND_TIMEOUT_SECONDS)

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 180L
    }
}
