package com.aspix2k.affected.build

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
        commands: List<CliCommand>,
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
        commands: List<CliCommand>,
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

    fun capture(workingDirectory: String, command: List<String>, timeoutSeconds: Long = 60): String? = try {
        val directory = File(workingDirectory).takeIf(File::isDirectory) ?: return null
        if (command.isEmpty() || timeoutSeconds <= 0) return null
        val commandLine = GeneralCommandLine(command)
            .withWorkDirectory(directory)
            .withCharset(Charsets.UTF_8)
        val timeoutMillis = TimeUnit.SECONDS.toMillis(timeoutSeconds).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val output = CapturingProcessHandler(commandLine).runProcess(timeoutMillis)
        output.stdout.takeIf { output.exitCode == 0 && !output.isTimeout && !output.isCancelled }
    } catch (error: CancellationException) {
        throw error
    } catch (error: ProcessCanceledException) {
        throw error
    } catch (error: Exception) {
        null
    }
}
