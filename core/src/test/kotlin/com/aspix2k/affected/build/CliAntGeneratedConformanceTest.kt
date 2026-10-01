package com.aspix2k.affected.build

import com.aspix2k.affected.build.process.CliCommand
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliAntGeneratedConformanceTest {

    @Test
    fun `ant generates sources before the test target`() = fixture("ant-generated") { root ->
        val commands = antCommands(root, listOf(".:test"))
        assertEquals(
            listOf(listOf("ant", "generate"), listOf("ant", "test")),
            commands.map(CliCommand::arguments),
        )
        val text = commands.joinToString("\n") { execute(root, it.arguments) }
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
