package com.aspix2k.affected.build

import com.aspix2k.affected.build.dart.dartCommands
import com.aspix2k.affected.build.dart.dartModules
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliDartWorkspaceConformanceTest {

    @Test
    fun `dart runs only the selected workspace package tests`() = fixture("dart-workspace") { root ->
        val modules = requireNotNull(dartModules(root)).filter(BuildModule::hasTests)
        assertEquals(listOf("packages/alpha", "packages/beta"), modules.map(BuildModule::executionId))
        val command = dartCommands(listOf("packages/alpha:test")).single()
        assertEquals(listOf("dart", "test", "packages/alpha/test"), command.arguments)
        resolve(root)
        val text = execute(root, command.arguments)
        assertContains(text, "AlphaTest")
        assertContains(text, "1 test passed")
        assertFalse("BetaTest" in text)
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

    private fun resolve(directory: File) {
        execute(directory, listOf("dart", "pub", "get"))
    }

    private fun execute(directory: File, arguments: List<String>): String =
        NativeProcessRunner.execute(arguments, directory, COMMAND_TIMEOUT_SECONDS)

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 180L
    }
}
