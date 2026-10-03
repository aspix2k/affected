package com.aspix2k.affected

import kotlinx.coroutines.CancellationException
import org.junit.Assume.assumeFalse
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChangeAnalyzerProcessTest {

    private class Cancelled : RuntimeException()

    private val directory = createTempDirectory("affected-git-process").toFile()

    private fun script(body: String): File = File(directory, "fake-git").apply {
        writeText("#!/bin/sh\n$body\n")
        setExecutable(true)
    }

    private fun sleepingGit(): Pair<File, File> {
        val pidFile = File(directory, "pid")
        return script("echo \$\$ > '${pidFile.absolutePath}'\nexec sleep 60") to pidFile
    }

    private fun pidOf(pidFile: File): Long? = pidFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()

    private fun awaitPid(pidFile: File): Long {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            pidOf(pidFile)?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        error("The fake git did not start")
    }

    private fun awaitGone(pid: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false).not()) return
            Thread.sleep(POLL_MILLIS)
        }
        error("The fake git $pid is still running")
    }

    private fun capture(git: File, timeoutMillis: Long = TIMEOUT_MILLIS, checkCanceled: () -> Unit = {}) =
        ChangeAnalyzer.capture(listOf(git.absolutePath), directory, System.getenv(), timeoutMillis, checkCanceled)

    @Test
    fun `a failing cancellation check stops Git and propagates unchanged`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val (git, pidFile) = sleepingGit()
        val analyzer = ChangeAnalyzer(directory, "main", gitExecutable = git.absolutePath) {
            if (pidOf(pidFile) != null) throw Cancelled()
        }

        assertFailsWith<Cancelled> { analyzer.isUsable() }

        awaitGone(awaitPid(pidFile))
    }

    @Test
    fun `an interrupted wait stops Git and surfaces as cancellation`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val (git, pidFile) = sleepingGit()
        var failure: Throwable? = null
        val worker = thread {
            failure = runCatching { capture(git) }.exceptionOrNull()
        }

        val pid = awaitPid(pidFile)
        worker.interrupt()
        worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))

        assertFalse(worker.isAlive)
        assertTrue(failure is CancellationException, "got $failure")
        awaitGone(pid)
    }

    @Test
    fun `a Git that does not finish is stopped and reported as a timeout`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val (git, pidFile) = sleepingGit()

        assertFailsWith<TimeoutException> { capture(git, timeoutMillis = SHORT_TIMEOUT_MILLIS) }

        awaitGone(awaitPid(pidFile))
    }

    @Test
    fun `large output is captured completely while errors are discarded`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val git = script("head -c $LARGE_OUTPUT /dev/zero | tr '\\0' a\nhead -c $LARGE_OUTPUT /dev/zero >&2\nexit 3")

        val output = capture(git)

        assertEquals(3, output.exitCode)
        assertEquals(LARGE_OUTPUT, output.stdout.length)
    }

    @Test
    fun `Git runs with literal pathspecs and the given environment`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val git = script("printf '%s' \"\$GIT_LITERAL_PATHSPECS\"")

        val output = ChangeAnalyzer.capture(
            listOf(git.absolutePath),
            directory,
            mapOf("GIT_LITERAL_PATHSPECS" to "1"),
            TIMEOUT_MILLIS,
        ) {}

        assertEquals("1", output.stdout)
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val POLL_MILLIS = 20L
        const val TIMEOUT_MILLIS = 30_000L
        const val SHORT_TIMEOUT_MILLIS = 2_000L
        const val LARGE_OUTPUT = 1_000_000
    }
}
