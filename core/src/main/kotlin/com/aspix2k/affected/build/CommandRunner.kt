package com.aspix2k.affected.build

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

object CommandRunner {

    fun run(project: Project, workingDirectory: String, command: List<String>, title: String) {
        runBatch(project, workingDirectory, listOf(CliCommand(title, command)), title)
    }

    suspend fun runAndWait(
        project: Project,
        workingDirectory: String,
        command: List<String>,
        title: String,
    ): Boolean = runBatchAndWait(project, workingDirectory, listOf(CliCommand(title, command)), title)

    internal fun runBatch(
        project: Project,
        workingDirectory: String,
        commands: List<CliStep>,
        title: String,
        unresolvedMessage: String? = null,
    ) {
        if (project.isDisposed) return

        val handler = SequentialProcessHandler(
            File(workingDirectory),
            commands,
            unresolvedMessage ?: DEFAULT_UNRESOLVED_MESSAGE,
        )
        ProcessTerminatedListener.attach(handler)

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                handler.destroyProcess()
                return@invokeLater
            }
            RunContentExecutor(project, handler)
                .withTitle(title)
                .withActivateToolWindow(true)
                .withStop({ handler.destroyProcess() }, { !handler.isProcessTerminated })
                .run()
        }
    }

    internal suspend fun runBatchAndWait(
        project: Project,
        workingDirectory: String,
        commands: List<CliStep>,
        title: String,
        unresolvedMessage: String? = null,
    ): Boolean {
        if (project.isDisposed) return false

        val handler = SequentialProcessHandler(
            File(workingDirectory),
            commands,
            unresolvedMessage ?: DEFAULT_UNRESOLVED_MESSAGE,
        )
        ProcessTerminatedListener.attach(handler)
        val completed = AtomicBoolean(false)

        return suspendCancellableCoroutine { continuation ->
            fun complete(passed: Boolean) {
                if (completed.compareAndSet(false, true) && continuation.isActive) continuation.resume(passed)
            }

            handler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    complete(event.exitCode == 0)
                }
            })
            continuation.invokeOnCancellation {
                if (!handler.isProcessTerminated) handler.destroyProcess()
            }

            ApplicationManager.getApplication().invokeLater {
                if (!continuation.isActive || project.isDisposed) {
                    if (!handler.isProcessTerminated) handler.destroyProcess()
                    complete(false)
                    return@invokeLater
                }
                RunContentExecutor(project, handler)
                    .withTitle(title)
                    .withActivateToolWindow(true)
                    .withStop({ handler.destroyProcess() }, { !handler.isProcessTerminated })
                    .run()
            }
        }
    }

    fun capture(
        workingDirectory: String,
        command: List<String>,
        timeoutSeconds: Long = 60,
        maxBytes: Int = DEFAULT_CAPTURE_LIMIT,
    ): String? = try {
        val directory = File(workingDirectory).takeIf(File::isDirectory) ?: return null
        if (command.isEmpty() || timeoutSeconds <= 0 || maxBytes <= 0) return null
        val commandLine = GeneralCommandLine(command)
            .withWorkDirectory(directory)
            .withCharset(Charsets.UTF_8)
        capture(commandLine.createProcess(), timeoutSeconds, maxBytes)
    } catch (error: CancellationException) {
        throw error
    } catch (error: ProcessCanceledException) {
        throw error
    } catch (error: Exception) {
        null
    }

    private fun capture(process: Process, timeoutSeconds: Long, maxBytes: Int): String? {
        val state = BoundedCapture(process, maxBytes)
        val stdout = state.reader(process.inputStream, collect = true)
        val stderr = state.reader(process.errorStream, collect = false)
        stdout.start()
        stderr.start()
        return try {
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) destroyProcessTree(process)
            stdout.join(CAPTURE_READER_TIMEOUT_MILLIS)
            stderr.join(CAPTURE_READER_TIMEOUT_MILLIS)
            if (stdout.isAlive || stderr.isAlive) {
                stdout.interrupt()
                stderr.interrupt()
                null
            } else {
                state.result().takeIf { completed && process.exitValue() == 0 }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            if (process.isAlive) destroyProcessTree(process)
        }
    }
}

private class BoundedCapture(private val process: Process, private val limit: Int) {

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
                        destroyProcessTree(process)
                        return@use
                    }
                    if (collect) stdout.write(buffer, 0, count)
                }
            }
        }.onFailure {
            failed.set(true)
            destroyProcessTree(process)
        }
    }.apply {
        isDaemon = true
        name = "affected-command-capture"
    }

    fun result(): String? = if (failed.get()) null else stdout.toString(StandardCharsets.UTF_8)
}

private fun destroyProcessTree(process: Process) {
    runCatching {
        process.descendants().toList().asReversed().forEach { descendant -> descendant.destroyForcibly() }
    }
    runCatching { process.destroyForcibly() }
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
    runCatching { process.outputStream.close() }
}

private const val DEFAULT_CAPTURE_LIMIT = 16 * 1024 * 1024
private const val CAPTURE_READER_TIMEOUT_MILLIS = 5_000L
