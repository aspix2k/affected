package com.aspix2k.affected.build

import com.aspix2k.affected.build.process.ProcessTreeTermination
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

internal data class NativeProcessResult(
    val pid: Long,
    val completed: Boolean,
    val exitCode: Int?,
    val output: String,
) {
    val passed: Boolean get() = completed && exitCode == 0
}

internal object NativeProcessRunner {

    fun run(
        command: List<String>,
        directory: File,
        timeoutSeconds: Long,
        environment: Map<String, String> = emptyMap(),
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
            supervise(command, builder.start(), timeoutSeconds, scheduler, output)
        } finally {
            scheduler.shutdownNow()
            assertTrue(scheduler.awaitTermination(TERMINATION_SECONDS, TimeUnit.SECONDS))
        }
    }

    fun execute(
        command: List<String>,
        directory: File,
        timeoutSeconds: Long,
        environment: Map<String, String> = emptyMap(),
        configure: ProcessBuilder.() -> Unit = {},
    ): String {
        val result = run(command, directory, timeoutSeconds, environment, configure)
        assertTrue(result.completed, "Timed out: ${command.joinToString(" ")}\n${result.output}")
        assertTrue(result.passed, "Failed: ${command.joinToString(" ")}\n${result.output}")
        return result.output
    }

    private fun supervise(
        command: List<String>,
        process: Process,
        timeoutSeconds: Long,
        scheduler: ScheduledExecutorService,
        output: File,
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
                assertTrue(
                    proven && exited,
                    "Process tree survived termination: ${command.joinToString(" ")}\n${output.readText()}",
                )
            }
            val exitCode = if (completed) process.exitValue() else null
            return NativeProcessResult(process.pid(), completed, exitCode, output.readText())
        } finally {
            termination.close()
        }
    }

    private const val TERMINATION_SECONDS = 15L
}
