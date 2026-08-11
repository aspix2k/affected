package com.aspix2k.affected.build

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SequentialProcessHandlerTest {

    @Test
    fun `commands share one handler and run in order`() {
        val output = StringBuilder()
        val workingDirectory = File(createTempDirectory("sequential-process").toFile(), "directory with spaces")
            .apply { mkdirs() }
        val handler = SequentialProcessHandler(
            workingDirectory,
            listOf(
                CliCommand("first", listOf(java(), "-version")),
                CliCommand("second", listOf(java(), "-version")),
            ),
        )
        handler.addProcessListener(listener(output))

        handler.startNotify()

        assertTrue(handler.waitFor(30_000))
        assertEquals(0, handler.exitCode)
        assertTrue(output.indexOf("> first") < output.indexOf("> second"), output.toString())
    }

    @Test
    fun `a failed command prevents later commands`() {
        val output = StringBuilder()
        val handler = SequentialProcessHandler(
            createTempDirectory("sequential-failure").toFile(),
            listOf(
                CliCommand("first", listOf(java(), "-version")),
                CliCommand("failure", listOf(java(), "--affected-invalid-option")),
                CliCommand("never", listOf(java(), "-version")),
            ),
        )
        handler.addProcessListener(listener(output))

        handler.startNotify()

        assertTrue(handler.waitFor(30_000))
        assertTrue(handler.exitCode != 0)
        assertFalse(output.contains("> never"), output.toString())
    }

    @Test
    fun `an unresolved command batch fails visibly`() {
        val output = StringBuilder()
        val handler = SequentialProcessHandler(
            createTempDirectory("sequential-empty").toFile(),
            emptyList(),
        )
        handler.addProcessListener(listener(output))

        handler.startNotify()

        assertTrue(handler.waitFor(30_000))
        assertTrue(handler.exitCode != 0)
        assertTrue(output.contains("could not resolve the planned modules"), output.toString())
    }

    private fun listener(output: StringBuilder): ProcessListener = object : ProcessListener {
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            output.append(event.text)
        }
    }

    private fun java(): String = File(
        System.getProperty("java.home"),
        if (System.getProperty("os.name").startsWith("Windows")) "bin/java.exe" else "bin/java",
    ).absolutePath
}
