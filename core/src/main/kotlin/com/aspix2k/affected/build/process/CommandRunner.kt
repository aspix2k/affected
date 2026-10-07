package com.aspix2k.affected.build.process

import com.aspix2k.affected.AffectedOwnedSession
import com.aspix2k.affected.AffectedRunPresentation
import com.aspix2k.affected.AffectedRunSessions
import com.aspix2k.affected.AffectedSettings
import com.aspix2k.affected.BaseCheckRun
import com.aspix2k.affected.ProcessAffectedRunChild
import com.aspix2k.affected.affectedRunLabel
import com.aspix2k.affected.build.continuesAfterFailure
import com.aspix2k.affected.build.projectExecutionRootGuard
import com.aspix2k.affected.currentAffectedRunPresentation
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object CommandRunner {

    private val LOG = logger<CommandRunner>()

    internal fun refuseInvalidExecutionRoot(project: Project, workingDirectory: String, title: String) {
        runBatch(project, workingDirectory, emptyList(), title)
    }

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
        continueAfterFailure: Boolean = planContinuesAfterFailure(),
    ) {
        if (project.isDisposed) return

        val handler = SequentialProcessHandler(
            File(workingDirectory),
            commands,
            unresolvedMessage ?: DEFAULT_UNRESOLVED_MESSAGE,
            continueAfterFailure = continueAfterFailure,
            executionRootGuard = projectExecutionRootGuard(
                Path.of(workingDirectory),
                project.basePath?.let(Path::of),
            ),
        )
        ProcessTerminatedListener.attach(handler)
        if (!AffectedRunSessions.getInstance(project).register(handler as AffectedOwnedSession)) {
            handler.startNotify()
            return
        }
        val presentation = currentAffectedRunPresentation()

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                handler.destroyProcess()
                handler.startNotify()
                return@invokeLater
            }
            showHandler(project, handler, title, workingDirectory, presentation)
        }
    }

    private suspend fun executionProjectRoot(project: Project): Path? =
        currentCoroutineContext()[BaseCheckRun]?.root ?: project.basePath?.let(Path::of)

    internal suspend fun runBatchAndWait(
        project: Project,
        workingDirectory: String,
        commands: List<CliStep>,
        title: String,
        unresolvedMessage: String? = null,
        continueAfterFailure: Boolean = planContinuesAfterFailure(),
        show: (Project, ProcessHandler, String, String, AffectedRunPresentation?) -> Unit = ::showHandler,
    ): Boolean {
        if (project.isDisposed) return false

        val handler = SequentialProcessHandler(
            File(workingDirectory),
            commands,
            unresolvedMessage ?: DEFAULT_UNRESOLVED_MESSAGE,
            continueAfterFailure = continueAfterFailure,
            executionRootGuard = projectExecutionRootGuard(
                Path.of(workingDirectory),
                executionProjectRoot(project),
            ),
        )
        ProcessTerminatedListener.attach(handler)
        val completed = AtomicBoolean(false)
        val aborted = AtomicBoolean(false)
        val terminated = CompletableDeferred<Unit>()
        val sessions = AffectedRunSessions.getInstance(project)
        val presentation = currentAffectedRunPresentation()
        var registered = false

        try {
            return suspendCancellableCoroutine { continuation ->
                fun complete(passed: Boolean) {
                    if (completed.compareAndSet(false, true) && continuation.isActive) continuation.resume(passed)
                }

                handler.addProcessListener(object : ProcessListener {
                    override fun processTerminated(event: ProcessEvent) {
                        terminated.complete(Unit)
                        complete(event.exitCode == 0 && !aborted.get())
                    }
                })
                registered = sessions.register(handler as AffectedOwnedSession)
                if (!registered) {
                    handler.startNotify()
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation {
                    if (!handler.isProcessTerminated) {
                        handler.destroyProcess()
                        handler.startNotify()
                    }
                }

                ApplicationManager.getApplication().invokeLater {
                    if (!continuation.isActive || project.isDisposed) {
                        aborted.set(true)
                        if (!handler.isProcessTerminated) handler.destroyProcess()
                        handler.startNotify()
                        return@invokeLater
                    }
                    showOrFail(show, project, handler, title, workingDirectory, presentation) { aborted.set(true) }
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (!handler.isProcessTerminated) {
                    handler.destroyProcess()
                    handler.startNotify()
                }
                terminated.await()
            }
            throw cancelled
        } finally {
            if (registered) sessions.unregister(handler as AffectedOwnedSession)
        }
    }

    private fun showOrFail(
        show: (Project, ProcessHandler, String, String, AffectedRunPresentation?) -> Unit,
        project: Project,
        handler: ProcessHandler,
        title: String,
        workingDirectory: String,
        presentation: AffectedRunPresentation?,
        onFailure: () -> Unit,
    ) {
        try {
            show(project, handler, title, workingDirectory, presentation)
        } catch (error: Exception) {
            LOG.warn("Affected could not show the run for $title", error)
            onFailure()
            if (!handler.isProcessTerminated) handler.destroyProcess()
            handler.startNotify()
        }
    }

    private fun showHandler(
        project: Project,
        handler: ProcessHandler,
        title: String,
        workingDirectory: String,
        presentation: AffectedRunPresentation?,
    ) {
        if (presentation != null) {
            presentation.attach(
                affectedRunLabel(title.removePrefix("Affected "), workingDirectory, project.basePath),
                ProcessAffectedRunChild(project, handler),
            )
            handler.startNotify()
            return
        }
        RunContentExecutor(project, handler)
            .withTitle(title)
            .withActivateToolWindow(true)
            .withStop({ handler.destroyProcess() }, { !handler.isProcessTerminated })
            .run()
    }

    fun capture(
        workingDirectory: String,
        command: List<String>,
        timeoutSeconds: Long = 60,
        maxBytes: Int = DEFAULT_CAPTURE_LIMIT,
        environment: Map<String, String> = emptyMap(),
    ): String? = ideCommandCapture.capture(workingDirectory, command, timeoutSeconds, maxBytes, environment)
}

private fun planContinuesAfterFailure(): Boolean =
    continuesAfterFailure(AffectedSettings.getInstance().stopAfterFirstFailure)
