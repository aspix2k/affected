package com.aspix2k.affected.build

import com.aspix2k.affected.build.dart.dartCommands
import com.aspix2k.affected.build.dart.dartProjectRoots
import com.aspix2k.affected.build.dart.dartRootModule
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliDartConformanceTest {

    @Test
    fun `dart runs the project test command for the affected root`() = fixture("dart") { root ->
        val modules = listOf(dartRootModule(root))
        val command = dartCommands(modules.map { "${it.executionId}:${it.testTask}" }).single()
        assertEquals(listOf("dart", "test"), command.arguments)
        resolve(root)
        val text = execute(root, command.arguments)
        assertContains(text, "AlphaTest")
        assertContains(text, "1 test passed")
    }

    @Test
    fun `dart runs from a single first-level nested package`() = fixture("dart", nested = true) { root ->
        val nested = dartProjectRoots(root).single()
        assertEquals(File(root, "pkg").canonicalFile, nested.canonicalFile)
        val module = dartRootModule(nested)
        val command = dartCommands(listOf("${module.executionId}:${module.testTask}")).single()
        resolve(nested)
        val text = execute(nested, command.arguments)
        assertContains(text, "AlphaTest")
        assertContains(text, "1 test passed")
    }

    private fun fixture(name: String, nested: Boolean = false, block: (File) -> Unit) {
        assumeTrue(System.getProperty("affected.cliConformance") == "true")
        val source = File(fixtureRoot(), name)
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        OwnedSandbox.use("affected-cli-$name") { sandbox ->
            val destination = if (nested) File(sandbox.root, "pkg") else sandbox.root
            assertTrue(source.copyRecursively(destination, overwrite = true), "Could not copy $source")
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
