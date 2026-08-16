package com.aspix2k.affected

import com.intellij.execution.actions.StopProcessAction
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentDescriptorReusePolicy
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent

internal interface AffectedRunChild : Disposable {
    val component: JComponent
    val preferredFocus: JComponent?
    fun stop()
}

internal interface AffectedRunView : Disposable {
    fun publish(handler: ProcessHandler)
    fun attach(label: String, child: AffectedRunChild)
}

internal fun affectedRunLabel(system: String, root: String, projectRoot: String?): String {
    val normalizedRoot = File(root).absoluteFile.normalize()
    val relative = projectRoot
        ?.let { normalizedRoot.relativeToOrNull(File(it).absoluteFile.normalize()) }
        ?.invariantSeparatorsPath
        ?.takeUnless { it.isBlank() || it == "." }
    return "$system · ${relative ?: normalizedRoot.name.ifBlank { root }}"
}

internal class ProcessAffectedRunChild(
    project: Project,
    private val handler: ProcessHandler,
) : AffectedRunChild {
    private val disposed = AtomicBoolean()
    private val console = TextConsoleBuilderFactory.getInstance()
        .createBuilder(project)
        .console
        .also { it.attachToProcess(handler) }

    override val component: JComponent get() = console.component
    override val preferredFocus: JComponent? get() = console.preferredFocusableComponent

    override fun stop() {
        if (!handler.isProcessTerminated) handler.destroyProcess()
    }

    override fun dispose() {
        if (disposed.compareAndSet(false, true)) console.dispose()
    }
}

private class IdeAffectedRunView(
    private val project: Project,
    private val closed: () -> Unit,
    private val publishContent: (RunContentDescriptor) -> Unit,
) : AffectedRunView {
    private val disposed = AtomicBoolean()
    private val nextContent = AtomicInteger()
    private val root = Disposer.newDisposable("Affected run")
    private lateinit var ui: RunnerLayoutUi
    private lateinit var descriptor: RunContentDescriptor

    override fun publish(handler: ProcessHandler) = onEdt {
        check(!disposed.get())
        ui = RunnerLayoutUi.Factory.getInstance(project)
            .create("Affected", "Affected", "Affected", root)
        descriptor = RunContentDescriptor(null, handler, ui.component, "Affected", null)
        descriptor.runnerLayoutUi = ui
        ui.options.setLeftToolbar(
            DefaultActionGroup(StopProcessAction("Stop Affected", "Stop Affected", handler)),
            "Affected",
        )
        descriptor.reusePolicy = object : RunContentDescriptorReusePolicy() {
            override fun canBeReusedBy(descriptor: RunContentDescriptor): Boolean = false
        }
        descriptor.isActivateToolWindowWhenAdded = true
        descriptor.isAutoFocusContent = true
        Disposer.register(descriptor, root)
        Disposer.register(descriptor, Disposable {
            disposed.set(true)
            closed()
        })
        ProcessTerminatedListener.attach(handler)
        publishContent(descriptor)
        handler.startNotify()
    }

    override fun attach(label: String, child: AffectedRunChild) = onEdt {
        check(!disposed.get())
        val id = "affected.child.${nextContent.incrementAndGet()}"
        ui.addContent(ui.createContent(id, child.component, label, null, child.preferredFocus))
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        onEdt {
            if (::descriptor.isInitialized) Disposer.dispose(descriptor) else Disposer.dispose(root)
        }
    }

    private fun onEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeAndWait(action)
    }
}

internal class AffectedRunPresentation(
    private val claim: AffectedRunClaim,
    private val view: AffectedRunView,
) : Disposable {

    companion object {
        fun open(project: Project, claim: AffectedRunClaim): AffectedRunPresentation = open(project, claim) {
            RunContentManager.getInstance(project).showRunContent(
                DefaultRunExecutor.getRunExecutorInstance(),
                it,
            )
        }

        internal fun open(
            project: Project,
            claim: AffectedRunClaim,
            publishContent: (RunContentDescriptor) -> Unit,
        ): AffectedRunPresentation {
            val lock = Any()
            var presentation: AffectedRunPresentation? = null
            var closeRequested = false
            val view = IdeAffectedRunView(
                project,
                closed = {
                    val current = synchronized(lock) {
                        presentation.also { if (it == null) closeRequested = true }
                    }
                    current?.dispose()
                },
                publishContent,
            )
            val created = AffectedRunPresentation(claim, view)
            val close = synchronized(lock) {
                presentation = created
                closeRequested
            }
            if (close) created.dispose()
            return created
        }
    }

    private enum class State {
        OPEN,
        STOPPING,
        FINISHED,
    }

    private val lock = Any()
    private val disposed = AtomicBoolean()
    private val children = LinkedHashSet<AffectedRunChild>()
    private var state = State.OPEN
    private val aggregateHandler = AggregateProcessHandler(::stop)

    init {
        try {
            view.publish(aggregateHandler)
            claim.bindCompletion(::complete)
        } catch (error: Exception) {
            try {
                aggregateHandler.finish(1)
            } finally {
                view.dispose()
            }
            throw error
        }
    }

    fun attach(label: String, child: AffectedRunChild): Boolean {
        val accepted = synchronized(lock) {
            if (state != State.OPEN) return@synchronized false
            children += child
            true
        }
        if (!accepted) {
            try {
                child.stop()
            } finally {
                child.dispose()
            }
            return false
        }
        return runCatching { view.attach(label, child) }.fold(
            onSuccess = { true },
            onFailure = {
                synchronized(lock) { children.remove(child) }
                try {
                    child.stop()
                } finally {
                    child.dispose()
                }
                false
            },
        )
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        stop()
        val owned = synchronized(lock) {
            if (state == State.FINISHED) {
                children.toList().also { children.clear() }
            } else {
                emptyList()
            }
        }
        try {
            forEachChild(owned, AffectedRunChild::dispose)
        } finally {
            view.dispose()
        }
    }

    private fun stop() {
        val owned = synchronized(lock) {
            if (state != State.OPEN) return
            state = State.STOPPING
            children.toList()
        }
        claim.stopIfActive()
        forEachChild(owned, AffectedRunChild::stop)
    }

    private fun complete(passed: Boolean) {
        val owned = synchronized(lock) {
            if (state == State.FINISHED) return
            state = State.FINISHED
            if (disposed.get()) {
                children.toList().also { children.clear() }
            } else {
                emptyList()
            }
        }
        try {
            aggregateHandler.finish(if (passed) 0 else 1)
        } finally {
            forEachChild(owned, AffectedRunChild::dispose)
        }
    }

    private fun forEachChild(owned: List<AffectedRunChild>, action: (AffectedRunChild) -> Unit) {
        var failure: Throwable? = null
        owned.forEach { child ->
            try {
                action(child)
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        failure?.let { throw it }
    }
}

private class AggregateProcessHandler(
    private val stop: () -> Unit,
) : ProcessHandler() {

    override fun destroyProcessImpl() = stop()

    override fun detachProcessImpl() = stop()

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null

    fun finish(exitCode: Int) {
        if (!isStartNotified) startNotify()
        notifyProcessTerminated(exitCode)
    }
}
