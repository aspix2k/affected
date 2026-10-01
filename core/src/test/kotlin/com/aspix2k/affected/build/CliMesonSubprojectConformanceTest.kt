package com.aspix2k.affected.build

import com.aspix2k.affected.build.process.CliCommand
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliMesonSubprojectConformanceTest {

    @Test
    fun `meson runs tests declared in an in-tree subproject`() = fixture("meson-subprojects") { root ->
        val module = mesonRootModule(root)
        assertTrue(module.hasTests)
        val commands = mesonCommands(root, listOf("${module.executionId}:${module.testTask}"))
        assertEquals(
            listOf(
                listOf("meson", "setup", "build"),
                listOf("meson", "test", "-C", "build"),
            ),
            commands.map(CliCommand::arguments),
        )
        val text = commands.joinToString("\n") { execute(root, it.arguments) }
        assertContains(text, "1/1")
        assertContains(text, "OK")
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
