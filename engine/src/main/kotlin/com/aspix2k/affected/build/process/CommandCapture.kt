package com.aspix2k.affected.build.process

import com.aspix2k.affected.build.executionRootGuard
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class CommandCapture(private val host: ProcessHost) {

    fun capture(
        workingDirectory: String,
        command: List<String>,
        timeoutSeconds: Long = 60,
        maxBytes: Int = DEFAULT_CAPTURE_LIMIT,
        environment: Map<String, String> = emptyMap(),
    ): String? = try {
        val directory = File(workingDirectory).takeIf(File::isDirectory) ?: return null
        if (command.isEmpty() || timeoutSeconds <= 0 || maxBytes <= 0) return null
        val guard = executionRootGuard(directory.toPath())
        if (guard.validationFailure() != null) return null
        val target = host.targetProcess(command, directory, environment)
        if (guard.validationFailure() != null) return null
        val contained = ContainedProcess.prepare(
            target,
            host.supervisorRuntime(),
            validateBeforeHelperLaunch = guard::validationFailure,
        )
        try {
            contained.start()
            captureProcessOutput(contained.process, contained, timeoutSeconds, maxBytes)
        } catch (error: Throwable) {
            contained.request()
            contained.await()
            throw error
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }
}

internal fun captureProcessOutput(
    process: Process,
    termination: ProcessTermination,
    timeoutSeconds: Long,
    maxBytes: Int,
): String? {
    val state = BoundedCapture(termination, maxBytes)
    val stdout = state.reader(process.inputStream, collect = true)
    val stderr = state.reader(process.errorStream, collect = false)
    stdout.start()
    stderr.start()
    var result: String? = null
    var readersTimedOut = false
    var restoreInterrupt = false
    try {
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) termination.request()
        stdout.join(CAPTURE_READER_TIMEOUT_MILLIS)
        stderr.join(CAPTURE_READER_TIMEOUT_MILLIS)
        if (stdout.isAlive || stderr.isAlive) {
            readersTimedOut = true
            termination.request()
            stdout.interrupt()
            stderr.interrupt()
        } else {
            result = state.result().takeIf {
                completed && process.exitValue() == 0 && !termination.isRequested
            }
        }
    } catch (_: InterruptedException) {
        restoreInterrupt = true
        termination.request()
    } finally {
        val cleanupSafe = finishCapture(process, termination, stdout, stderr, restoreInterrupt)
        if (!cleanupSafe || readersTimedOut) result = null
    }
    return result
}

private fun finishCapture(
    process: Process,
    termination: ProcessTermination,
    stdout: Thread,
    stderr: Thread,
    restoreInitialInterrupt: Boolean,
): Boolean {
    var restoreInterrupt = Thread.interrupted() || restoreInitialInterrupt
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
    runCatching { process.outputStream.close() }
    stdout.interrupt()
    stderr.interrupt()
    try {
        stdout.join(CAPTURE_READER_TIMEOUT_MILLIS)
        stderr.join(CAPTURE_READER_TIMEOUT_MILLIS)
    } catch (_: InterruptedException) {
        restoreInterrupt = true
        termination.request()
    }
    if (listOf(stdout, stderr).any(Thread::isAlive)) termination.request()
    var terminated = if (process.isAlive || termination.isRequested) {
        termination.await()
    } else {
        termination.close()
    }
    val interruptedDuringTermination = Thread.interrupted()
    if (!terminated && interruptedDuringTermination) {
        restoreInterrupt = true
        terminated = termination.await()
    }
    restoreInterrupt = Thread.interrupted() || interruptedDuringTermination || restoreInterrupt
    if (restoreInterrupt) Thread.currentThread().interrupt()
    return terminated && listOf(stdout, stderr).none(Thread::isAlive)
}

private class BoundedCapture(private val termination: ProcessTermination, private val limit: Int) {

    private val bytes = AtomicLong()
    private val failed = AtomicBoolean()
    private val stdout = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))

    fun reader(stream: InputStream, collect: Boolean): Thread = Thread {
        runCatching {
            stream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) return@use
                    if (bytes.addAndGet(count.toLong()) > limit) {
                        failed.set(true)
                        termination.request()
                        return@use
                    }
                    if (collect) stdout.write(buffer, 0, count)
                }
            }
        }.onFailure {
            failed.set(true)
            termination.request()
        }
    }.apply {
        isDaemon = true
        name = "affected-command-capture"
    }

    fun result(): String? = if (failed.get()) null else stdout.toString(StandardCharsets.UTF_8)
}

internal const val DEFAULT_CAPTURE_LIMIT = 16 * 1024 * 1024
private const val CAPTURE_READER_TIMEOUT_MILLIS = 5_000L
