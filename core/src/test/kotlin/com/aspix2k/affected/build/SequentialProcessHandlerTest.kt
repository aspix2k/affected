package com.aspix2k.affected.build

import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SequentialProcessHandlerTest {

    @Test
    fun `a replaced direct command root fails before the child starts`() {
        val project = createTempDirectory("sequential-root-direct")
        val root = project.resolve("module")
        val outside = createTempDirectory("sequential-root-direct-outside")
        Files.createDirectory(root)
        val marker = outside.resolve("started.marker")
        val temporary = Files.createTempDirectory("affected-handler-stale-root-")
        val handler = SequentialProcessHandler(
            root.toFile(),
            listOf(
                CliCommand(
                    "direct",
                    markerCommand(marker),
                    ownedTemporaryDirectories = listOf(temporary),
                ),
            ),
            executionRootGuard = PlannedExecutionRoot.capture(root).bind(project),
        )
        Files.delete(root)
        assumeTrue(runCatching { Files.createSymbolicLink(root, outside) }.isSuccess)
        val output = StringBuilder()
        handler.addProcessListener(listener(output))

        handler.startNotify()

        assertTrue(handler.waitFor(5_000))
        assertTrue(handler.exitCode != 0)
        assertFalse(Files.exists(marker))
        assertFalse(Files.exists(temporary))
        assertTrue(output.contains("planned working directory"), output.toString())
    }

    @Test
    fun `a deferred command revalidates its root after resolution`() {
        val project = createTempDirectory("sequential-root-deferred")
        val root = project.resolve("module")
        val outside = createTempDirectory("sequential-root-deferred-outside")
        Files.createDirectory(root)
        val marker = outside.resolve("started.marker")
        val handler = SequentialProcessHandler(
            root.toFile(),
            listOf(
                DeferredCliCommand.command {
                    Files.delete(root)
                    Files.createSymbolicLink(root, outside)
                    CliCommand("deferred", markerCommand(marker))
                },
            ),
            executionRootGuard = PlannedExecutionRoot.capture(root).bind(project),
        )
        val output = StringBuilder()
        handler.addProcessListener(listener(output))

        handler.startNotify()

        assertTrue(handler.waitFor(5_000))
        assertTrue(handler.exitCode != 0)
        assertFalse(Files.exists(marker))
        assertTrue(output.contains("planned working directory"), output.toString())
    }

    @Test
    fun `a deferred metadata child inherits the planned root identity`() {
        val project = createTempDirectory("sequential-root-capture")
        val root = project.resolve("module")
        val outside = createTempDirectory("sequential-root-capture-outside")
        Files.createDirectory(root)
        val marker = outside.resolve("captured.marker")
        val handler = SequentialProcessHandler(
            root.toFile(),
            listOf(
                DeferredCliCommand.command {
                    Files.delete(root)
                    Files.createSymbolicLink(root, outside)
                    val captured = CommandRunner.capture(root.toString(), markerCommand(marker))
                    check(captured == null)
                    CliCommand("deferred capture", markerCommand(marker))
                },
            ),
            executionRootGuard = PlannedExecutionRoot.capture(root).bind(project),
        )

        handler.startNotify()

        assertTrue(handler.waitFor(5_000))
        assertTrue(handler.exitCode != 0)
        assertFalse(Files.exists(marker))
    }

    @Test
    fun `a final root check runs after command listeners`() {
        val project = createTempDirectory("sequential-root-listener")
        val root = project.resolve("module")
        val outside = createTempDirectory("sequential-root-listener-outside")
        Files.createDirectory(root)
        val marker = outside.resolve("started.marker")
        val handler = SequentialProcessHandler(
            root.toFile(),
            listOf(CliCommand("listener swap", markerCommand(marker))),
            executionRootGuard = PlannedExecutionRoot.capture(root).bind(project),
        )
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (event.text.contains("> listener swap")) {
                    Files.delete(root)
                    Files.createSymbolicLink(root, outside)
                }
            }
        })

        handler.startNotify()

        assertTrue(handler.waitFor(5_000))
        assertTrue(handler.exitCode != 0)
        assertFalse(Files.exists(marker))
    }

    @Test
    fun `the helper launch revalidates a root replaced after the final handler check`() {
        val project = createTempDirectory("sequential-root-helper-launch")
        val root = project.resolve("module").createDirectory()
        val outside = createTempDirectory("sequential-root-helper-launch-outside")
        val marker = java.nio.file.Path.of("started.marker")
        val handler = SequentialProcessHandler(
            root.toFile(),
            listOf(CliCommand("helper launch swap", markerCommand(marker))),
            executionRootGuard = PlannedExecutionRoot.capture(root).bind(project),
            beforeHelperLaunch = {
                Files.delete(root)
                Files.createSymbolicLink(root, outside)
            },
        )

        handler.startNotify()

        assertTrue(handler.waitFor(5_000))
        assertTrue(handler.exitCode != 0)
        assertFalse(Files.exists(outside.resolve(marker)))
    }

    @Test
    fun `stopping root refusal waits for pending cleanup`() {
        val project = createTempDirectory("sequential-root-cleanup")
        val root = project.resolve("module").createDirectory()
        val guard = PlannedExecutionRoot.capture(root).bind(project)
        val temporary = Files.createTempDirectory("affected-handler-root-refusal-")
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        Files.delete(root)
        Files.createDirectory(root)
        val handler = SequentialProcessHandler(
            root.toFile(),
            listOf(
                CliCommand(
                    "pending cleanup",
                    listOf(java(), "-version"),
                    ownedTemporaryDirectories = listOf(temporary),
                ),
            ),
            executionRootGuard = guard,
            ownedTemporaryDirectoryCleanup = { directory ->
                cleanupStarted.countDown()
                releaseCleanup.await()
                Files.delete(directory)
                true
            },
        )

        handler.startNotify()
        assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS))
        handler.destroyProcess()

        assertFalse(handler.waitFor(100))
        assertTrue(Files.isDirectory(temporary))
        releaseCleanup.countDown()
        assertTrue(handler.waitFor(5_000))
        assertTrue(handler.exitCode != 0)
        assertFalse(Files.exists(temporary))
    }

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
    fun `a failed deferred resolution fails visibly`() {
        val output = StringBuilder()
        val handler = SequentialProcessHandler(
            createTempDirectory("sequential-resolution-error").toFile(),
            listOf(DeferredCliCommand("resolve") { error("active SDK is unavailable") }),
        )
        handler.addProcessListener(listener(output))

        handler.startNotify()

        assertTrue(handler.waitFor(30_000))
        assertTrue(handler.exitCode != 0)
        assertTrue(output.contains("could not resolve the next command"), output.toString())
        assertTrue(output.contains("active SDK is unavailable"), output.toString())
    }

    @Test
    fun `stopping the run interrupts deferred resolution`() {
        val entered = CountDownLatch(1)
        val resolved = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val output = StringBuilder()
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
                    listOf(java(), "-version")
                },
            ),
        )
        handler.addProcessListener(listener(output))

        handler.startNotify()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        handler.destroyProcess()

        assertTrue(handler.waitFor(5_000))
        assertTrue(resolved.await(5, TimeUnit.SECONDS))
        assertTrue(interrupted.get())
        assertTrue(handler.exitCode != 0)
        assertFalse(output.contains("> resolve"), output.toString())
    }

    @Test
    fun `stopping deferred resolution waits for owned cleanup`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var temporary: java.nio.file.Path
        val handler = SequentialProcessHandler(
            createTempDirectory("sequential-deferred-cleanup").toFile(),
            listOf(
                DeferredCliCommand.command {
                    temporary = Files.createTempDirectory("affected-handler-deferred-")
                    val command = CliCommand(
                        "deferred cleanup",
                        listOf(java(), "-version"),
                        ownedTemporaryDirectories = listOf(temporary),
                    )
                    entered.countDown()
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        while (release.count > 0) Thread.onSpinWait()
                        Thread.currentThread().interrupt()
                    }
                    command
                },
            ),
        )

        handler.startNotify()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        handler.destroyProcess()

        assertFalse(handler.waitFor(100))
        assertTrue(Files.isDirectory(temporary))
        release.countDown()
        assertTrue(handler.waitFor(5_000))
        assertTrue(handler.exitCode != 0)
        assertFalse(Files.exists(temporary))
    }

    @Test
    fun `stopping command startup waits for owned cleanup`() {
        val starting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val temporary = Files.createTempDirectory("affected-handler-starting-")
        val handler = SequentialProcessHandler(
            createTempDirectory("sequential-starting-cleanup").toFile(),
            listOf(
                DeferredCliCommand.command {
                    CliCommand(
                        "starting cleanup",
                        listOf(java(), "-version"),
                        ownedTemporaryDirectories = listOf(temporary),
                    )
                },
            ),
            processFactory = { commandLine, afterInitialPass ->
                starting.countDown()
                release.await()
                val processHandler = OSProcessHandler(commandLine)
                RunningCommand(
                    processHandler,
                    ProcessTreeTermination(
                        processHandler.process.toHandle(),
                        afterInitialPass = afterInitialPass,
                    ),
                    {},
                )
            },
        )

        handler.startNotify()
        assertTrue(starting.await(5, TimeUnit.SECONDS))
        handler.destroyProcess()

        assertFalse(handler.waitFor(100))
        assertTrue(Files.isDirectory(temporary))
        release.countDown()
        assertTrue(handler.waitFor(5_000))
        assertTrue(handler.exitCode != 0)
        assertFalse(Files.exists(temporary))
    }

    @Test
    fun `a completed command removes its owned temporary directory`() {
        val temporary = Files.createTempDirectory("affected-handler-cleanup-")
        temporary.resolve("nested").toFile().mkdirs()
        temporary.resolve("nested/result.txt").toFile().writeText("result")
        val handler = SequentialProcessHandler(
            createTempDirectory("sequential-cleanup").toFile(),
            listOf(
                CliCommand(
                    "cleanup",
                    listOf(java(), "-version"),
                    ownedTemporaryDirectories = listOf(temporary),
                ),
            ),
        )

        handler.startNotify()

        assertTrue(handler.waitFor(30_000))
        assertEquals(0, handler.exitCode)
        assertFalse(Files.exists(temporary))
    }

    @Test
    fun `an unsafe cleanup replacement fails without following the link`() {
        val temporary = Files.createTempDirectory("affected-handler-replaced-")
        val external = createTempDirectory("sequential-cleanup-external")
        external.resolve("keep.txt").toFile().writeText("keep")
        val probe = temporary.resolveSibling("${temporary.fileName}-probe")
        assumeTrue(
            runCatching {
                Files.createSymbolicLink(probe, external)
                Files.delete(probe)
            }.isSuccess,
        )
        val command = CliCommand(
            "cleanup",
            listOf(java(), "-version"),
            ownedTemporaryDirectories = listOf(temporary),
        )
        val handler = SequentialProcessHandler(
            createTempDirectory("sequential-cleanup-replaced").toFile(),
            listOf(
                DeferredCliCommand.command {
                    temporary.toFile().deleteRecursively()
                    Files.createSymbolicLink(temporary, external)
                    command
                },
            ),
        )

        handler.startNotify()

        assertTrue(handler.waitFor(30_000))
        assertTrue(handler.exitCode != 0)
        assertTrue(external.resolve("keep.txt").toFile().isFile)
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

    private fun markerCommand(marker: java.nio.file.Path): List<String> = listOf(
        java(),
        "-cp",
        System.getProperty("java.class.path"),
        MarkerWriter::class.java.name,
        marker.toString(),
    )
}

private object MarkerWriter {
    @JvmStatic
    fun main(arguments: Array<String>) {
        Files.writeString(java.nio.file.Path.of(arguments.single()), "started")
    }
}
