package com.aspix2k.affected.build

import com.aspix2k.affected.build.process.ProcessTreeTermination
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

internal data class NativeProcessResult(
    val pid: Long,
    val completed: Boolean,
    val exitCode: Int?,
    val output: String,
    val truncated: Boolean = false,
) {
    val passed: Boolean get() = completed && exitCode == 0
}

internal object NativeProcessRunner {

    fun run(
        command: List<String>,
        directory: File,
        timeoutSeconds: Long,
        environment: Map<String, String> = emptyMap(),
        outputLimitBytes: Int = DEFAULT_OUTPUT_LIMIT_BYTES,
        charset: Charset = Charsets.UTF_8,
        configure: ProcessBuilder.() -> Unit = {},
    ): NativeProcessResult = OwnedSandbox.use("affected-native-process") { sandbox ->
        val output = sandbox.file("output.log")
        val builder = ProcessBuilder(command)
            .directory(directory)
            .redirectErrorStream(true)
            .redirectOutput(output)
            .apply(configure)
        builder.environment().putAll(environment)
        val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "affected-native-process-termination").apply { isDaemon = true }
        }
        try {
            supervise(
                command,
                builder.start(),
                timeoutSeconds,
                scheduler,
                BoundedOutput(output, outputLimitBytes, charset),
            )
        } finally {
            shutdown(scheduler)
        }
    }

    private fun shutdown(scheduler: ScheduledExecutorService) {
        scheduler.shutdownNow()
        val wasInterrupted = Thread.interrupted()
        try {
            assertTrue(scheduler.awaitTermination(TERMINATION_SECONDS, TimeUnit.SECONDS))
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    fun execute(
        command: List<String>,
        directory: File,
        timeoutSeconds: Long,
        environment: Map<String, String> = emptyMap(),
        configure: ProcessBuilder.() -> Unit = {},
    ): String {
        val result = run(command, directory, timeoutSeconds, environment, configure = configure)
        assertTrue(result.completed, "Timed out: ${command.joinToString(" ")}\n${result.output}")
        assertTrue(result.passed, "Failed: ${command.joinToString(" ")}\n${result.output}")
        return result.output
    }

    private fun supervise(
        command: List<String>,
        process: Process,
        timeoutSeconds: Long,
        scheduler: ScheduledExecutorService,
        output: BoundedOutput,
    ): NativeProcessResult {
        val termination = ProcessTreeTermination(
            process.toHandle(),
            timeoutNanos = TimeUnit.SECONDS.toNanos(TERMINATION_SECONDS),
            executor = scheduler,
        )
        try {
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                val proven = termination.await()
                val exited = process.waitFor(TERMINATION_SECONDS, TimeUnit.SECONDS)
                if (!proven || !exited) {
                    throw AssertionError(
                        "Process tree survived termination: ${command.joinToString(" ")}\n${output.read().text}",
                    )
                }
            }
            val exitCode = if (completed) process.exitValue() else null
            val captured = output.read()
            return NativeProcessResult(process.pid(), completed, exitCode, captured.text, captured.truncated)
        } catch (interrupted: InterruptedException) {
            val proven = termination.await()
            val exited = !process.isAlive
            Thread.currentThread().interrupt()
            if (!proven || !exited) {
                interrupted.addSuppressed(
                    AssertionError("Process tree survived interruption: ${command.joinToString(" ")}"),
                )
            }
            throw interrupted
        } finally {
            termination.close()
        }
    }

    private class BoundedOutput(
        private val file: File,
        private val limitBytes: Int,
        private val charset: Charset,
    ) {
        fun read(): Captured {
            val bytes = file.inputStream().use { stream -> stream.readNBytes(limitBytes + 1) }
            return Captured(String(bytes, 0, minOf(bytes.size, limitBytes), charset), bytes.size > limitBytes)
        }
    }

    private class Captured(val text: String, val truncated: Boolean)

    private const val TERMINATION_SECONDS = 15L
    private const val DEFAULT_OUTPUT_LIMIT_BYTES = 1024 * 1024
}
