package com.aspix2k.affected.build

import com.aspix2k.affected.AffectedRunSessions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandRunnerCancellationTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testCancellationBeforeEdtLaunchCleansOwnedOutputBeforeReturning() = runBlocking {
        val sessions = AffectedRunSessions.getInstance(project)
        val temporary = Files.createTempDirectory("affected-command-prestart-")
        val edtBlocked = CompletableDeferred<Unit>()
        val releaseEdt = CountDownLatch(1)
        ApplicationManager.getApplication().invokeLater {
            edtBlocked.complete(Unit)
            releaseEdt.await()
        }
        edtBlocked.await()
        val running = async(Dispatchers.Default) {
            CommandRunner.runBatchAndWait(
                project,
                checkNotNull(project.basePath),
                listOf(
                    CliCommand(
                        "pre-start cleanup",
                        listOf(java(), "-version"),
                        ownedTemporaryDirectories = listOf(temporary),
                    ),
                ),
                "Affected pre-start cleanup",
            )
        }

        try {
            withTimeout(5_000) {
                while (sessions.activeCount() == 0) delay(25)
            }
            running.cancel()
            val completedBeforeEdt = withTimeoutOrNull(1_000) {
                assertFailsWith<CancellationException> { running.await() }
                true
            } == true
            val cleanedBeforeEdt = !Files.exists(temporary)

            kotlin.test.assertTrue(completedBeforeEdt, "Cancellation waited for the queued EDT launch")
            kotlin.test.assertTrue(cleanedBeforeEdt, "Cancellation returned before owned output was removed")
            kotlin.test.assertEquals(0, sessions.activeCount())
        } finally {
            releaseEdt.countDown()
            running.cancel()
            runCatching { withTimeout(5_000) { running.await() } }
            sessions.stopOwned()
            kotlin.test.assertTrue(
                !Files.exists(temporary) || temporary.toFile().deleteRecursively(),
                "Failed to remove $temporary",
            )
        }
    }

    fun testCaptureTerminatesTrackedDescendantHoldingOutputBeforeReturning() {
        val workingDirectory = Files.createTempDirectory("affected-capture-work-")
        val pid = Files.createTempFile("affected-capture-child-", ".pid")
        Files.delete(pid)
        var child: ProcessHandle? = null

        try {
            val output = CommandRunner.capture(
                workingDirectory.toString(),
                inheritedOutputCommand(pid),
                timeoutSeconds = 10,
            )
            assertNull(output)
            assertTrue(Files.isRegularFile(pid), "The descendant pid was not published")
            child = ProcessHandle.of(Files.readString(pid).trim().toLong()).orElse(null)
            assertFalse(child?.isAlive == true, "Capture returned while its tracked descendant was still alive")
        } finally {
            child?.takeIf(ProcessHandle::isAlive)?.destroyForcibly()
            Files.deleteIfExists(pid)
            assertTrue(workingDirectory.toFile().deleteRecursively())
        }
    }

    fun testInterruptedCaptureTerminatesTrackedDescendantBeforeReturning() {
        val workingDirectory = Files.createTempDirectory("affected-interrupted-capture-work-")
        val pid = Files.createTempFile("affected-interrupted-capture-child-", ".pid")
        Files.delete(pid)
        val result = AtomicReference<String?>()
        var child: ProcessHandle? = null
        val process = ProcessBuilder(inheritedOutputCommand(pid))
            .directory(workingDirectory.toFile())
            .start()
        val controlled = BlockingInputProcess(process)
        val capture = Thread {
            result.set(CommandRunner.capture(controlled, timeoutSeconds = 10, maxBytes = 1024))
        }

        try {
            capture.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!Files.isRegularFile(pid) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(Files.isRegularFile(pid), "The descendant pid was not published")
            child = ProcessHandle.of(Files.readString(pid).trim().toLong()).orElse(null)
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "The root process did not exit")
            capture.interrupt()
            capture.join(10_000)

            assertFalse(capture.isAlive, "Interrupted capture did not return")
            assertNull(result.get())
            assertFalse(child?.isAlive == true, "Interrupted capture returned before its descendant terminated")
        } finally {
            controlled.releaseInput()
            capture.interrupt()
            capture.join(10_000)
            child?.takeIf(ProcessHandle::isAlive)?.destroyForcibly()
            process.takeIf(Process::isAlive)?.destroyForcibly()
            Files.deleteIfExists(pid)
            assertTrue(workingDirectory.toFile().deleteRecursively())
        }
    }

    private fun java(): String = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
    ).toString()

    private fun inheritedOutputCommand(pid: Path): List<String> =
        if (System.getProperty("os.name").startsWith("Windows")) {
            listOf(
                java(),
                "-cp",
                System.getProperty("java.class.path"),
                InheritedOutputSpawner::class.java.name,
                pid.toString(),
            )
        } else {
            listOf(
                "/bin/sh",
                "-c",
                "sleep 60 & child=${'$'}!; printf %s \"${'$'}child\" > \"${'$'}1\"; sleep 1",
                "affected",
                pid.toString(),
            )
        }
}

private class BlockingInputProcess(private val process: Process) : Process() {
    private val input = BlockingInputStream()

    fun releaseInput() = input.release()

    override fun getOutputStream(): OutputStream = process.outputStream

    override fun getInputStream(): InputStream = input

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int = process.waitFor()

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = process.waitFor(timeout, unit)

    override fun exitValue(): Int = process.exitValue()

    override fun destroy() = process.destroy()

    override fun destroyForcibly(): Process = process.destroyForcibly()

    override fun isAlive(): Boolean = process.isAlive

    override fun pid(): Long = process.pid()

    override fun toHandle(): ProcessHandle = process.toHandle()
}

private class BlockingInputStream : InputStream() {
    private val released = CountDownLatch(1)

    fun release() = released.countDown()

    override fun read(): Int {
        while (true) {
            try {
                released.await()
                throw IOException("released")
            } catch (_: InterruptedException) {
                continue
            }
        }
    }

    override fun close() = Unit
}

private object InheritedOutputSpawner {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val command = if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("cmd.exe", "/c", "ping -n 60 127.0.0.1 > NUL")
        } else {
            listOf("sleep", "60")
        }
        val child = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        Files.writeString(Path.of(arguments.single()), child.pid().toString())
        Thread.sleep(TimeUnit.MILLISECONDS.toMillis(500))
    }
}
