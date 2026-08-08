package com.aspix2k.affected.build

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object CommandRunner {

    fun run(project: Project, workingDirectory: String, command: List<String>, title: String) {
        val commandLine = GeneralCommandLine(command)
            .withWorkDirectory(File(workingDirectory))
            .withCharset(Charsets.UTF_8)

        val handler = OSProcessHandler(commandLine)
        ProcessTerminatedListener.attach(handler)

        ApplicationManager.getApplication().invokeLater {
            RunContentExecutor(project, handler)
                .withTitle(title)
                .withActivateToolWindow(true)
                .withStop({ handler.destroyProcess() }, { !handler.isProcessTerminated })
                .run()
        }
    }

    suspend fun runAndWait(project: Project, workingDirectory: String, command: List<String>, title: String): Boolean {
        val commandLine = GeneralCommandLine(command)
            .withWorkDirectory(File(workingDirectory))
            .withCharset(Charsets.UTF_8)

        val handler = OSProcessHandler(commandLine)
        ProcessTerminatedListener.attach(handler)
        val completed = AtomicBoolean(false)

        return suspendCancellableCoroutine { continuation ->
            handler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    if (completed.compareAndSet(false, true)) continuation.resume(event.exitCode == 0)
                }
            })
            continuation.invokeOnCancellation {
                if (!handler.isProcessTerminated) handler.destroyProcess()
            }

            ApplicationManager.getApplication().invokeLater {
                if (!continuation.isActive) return@invokeLater
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
    } catch (e: Exception) {
        null
    }
}
