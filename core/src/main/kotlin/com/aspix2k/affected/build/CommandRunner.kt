package com.aspix2k.affected.build

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.io.File

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

    /** Runs in the same console, waits for the process, and reports its exit code. */
    fun runAndWait(project: Project, workingDirectory: String, command: List<String>, title: String): Boolean {
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

        handler.waitFor()
        return handler.exitCode == 0
    }

    fun capture(workingDirectory: String, command: List<String>, timeoutSeconds: Long = 60): String? = try {
        val process = ProcessBuilder(command)
            .directory(File(workingDirectory))
            .redirectErrorStream(false)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        if (process.exitValue() == 0) output else null
    } catch (e: Exception) {
        null
    }
}
