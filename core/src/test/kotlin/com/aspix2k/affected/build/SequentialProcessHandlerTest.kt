package com.aspix2k.affected.build

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    fun `command environment reaches the child process`() {
        val output = StringBuilder()
        val windows = System.getProperty("os.name").startsWith("Windows")
        val arguments = if (windows) listOf("cmd", "/c", "set", "AFFECTED_PROCESS_VALUE") else listOf("env")
        val handler = SequentialProcessHandler(
            createTempDirectory("sequential-environment").toFile(),
            listOf(CliCommand("environment", arguments, mapOf("AFFECTED_PROCESS_VALUE" to "proof"))),
        )
        handler.addProcessListener(listener(output))

        handler.startNotify()

        assertTrue(handler.waitFor(30_000))
        assertEquals(0, handler.exitCode)
        assertTrue(output.contains("AFFECTED_PROCESS_VALUE=proof"), output.toString())
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
    fun `a plan-level continue runs later commands after a failure`() {
        val output = StringBuilder()
        val handler = SequentialProcessHandler(
            createTempDirectory("sequential-plan-continue").toFile(),
            listOf(
                CliCommand("failure", listOf(java(), "--affected-invalid-option")),
                CliCommand("remainder", listOf(java(), "-version")),
            ),
            continueAfterFailure = true,
        )
        handler.addProcessListener(listener(output))

        handler.startNotify()

        assertTrue(handler.waitFor(30_000))
        assertTrue(handler.exitCode != 0)
        assertTrue(output.contains("> remainder"), output.toString())
    }

    @Test
    fun `a non fail fast command runs the remainder and preserves failure`() {
        val output = StringBuilder()
        val handler = SequentialProcessHandler(
            createTempDirectory("sequential-non-fail-fast").toFile(),
            listOf(
                CliCommand(
                    "failure",
                    listOf(java(), "--affected-invalid-option"),
                    continueOnFailure = true,
                ),
                CliCommand("remainder", listOf(java(), "-version")),
            ),
        )
        handler.addProcessListener(listener(output))

        handler.startNotify()

        assertTrue(handler.waitFor(30_000))
        assertTrue(handler.exitCode != 0)
        assertTrue(output.contains("> remainder"), output.toString())
    }

    @Test
    fun `a deferred command resolves only after the previous command completes`() {
        val output = StringBuilder()
        val firstCompleted = AtomicBoolean()
        val handler = SequentialProcessHandler(
            createTempDirectory("sequential-deferred").toFile(),
            listOf(
                CliCommand("first", listOf(java(), "-version")),
                DeferredCliCommand("second") {
                    check(firstCompleted.get())
                    listOf(java(), "-version")
                },
            ),
        )
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                output.append(event.text)
                if (event.text.contains("> first")) firstCompleted.set(true)
            }
        })

        handler.startNotify()

        assertTrue(handler.waitFor(30_000))
        assertEquals(0, handler.exitCode)
        assertTrue(output.indexOf("> first") < output.indexOf("> second"), output.toString())
    }

    @Test
    fun `an empty deferred selection skips the command without ending the session`() {
        val output = StringBuilder()
        val handler = SequentialProcessHandler(
            createTempDirectory("sequential-skipped").toFile(),
            listOf(
                CliCommand("build", listOf(java(), "-version")),
                DeferredCliCommand("empty") { null },
                CliCommand("consumer", listOf(java(), "-version")),
            ),
        )
        handler.addProcessListener(listener(output))

        handler.startNotify()

        assertTrue(handler.waitFor(30_000))
        assertEquals(0, handler.exitCode)
        assertFalse(output.contains("> empty"), output.toString())
        assertTrue(output.indexOf("> build") < output.indexOf("> consumer"), output.toString())
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

    @Test
    fun `stopping the run interrupts deferred resolution`() {
        val entered = CountDownLatch(1)
        val resolved = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val handler = SequentialProcessHandler(
            createTempDirectory("sequential-cancelled").toFile(),
            listOf(
                DeferredCliCommand("resolve") {
                    entered.countDown()
                    try {
                        Thread.sleep(30_000)
                    } catch (_: InterruptedException) {
                        interrupted.set(true)
                    } finally {
                        resolved.countDown()
                    }
                    null
                },
            ),
        )

        handler.startNotify()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        handler.destroyProcess()

        assertTrue(handler.waitFor(5_000))
        assertTrue(resolved.await(5, TimeUnit.SECONDS))
        assertTrue(interrupted.get())
        assertTrue(handler.exitCode != 0)
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
