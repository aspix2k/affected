package com.aspix2k.affected

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.UserDataHolderBase
import java.util.concurrent.atomic.AtomicBoolean

internal class AffectedExternalRunBinding private constructor(
    project: Project,
    private val presentation: AffectedRunPresentation,
    private val label: String,
    private val initialOutput: String,
    private val enableHeadless: (ExecutionEnvironment) -> Boolean,
    private val matches: (ExecutionEnvironment, Any) -> Boolean,
) : ExecutionListener, Disposable {
    private val marker = Any()
    private val disposed = AtomicBoolean()
    private val claimed = AtomicBoolean()
    private val connection = project.messageBus.connect()

    val userData = UserDataHolderBase().apply { putUserData(MARKER, marker) }

    init {
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, this)
    }

    override fun processStartScheduled(executorId: String, environment: ExecutionEnvironment) {
        if (disposed.get() || !matches(environment, marker)) return
        environment.contentToReuse = null
        if (!claimed.compareAndSet(false, true) || !enableHeadless(environment)) {
            environment.putUserData(FAILED, true)
            environment.putUserData(ExecutionManager.EXECUTION_SKIP_RUN, true)
        }
        environment.putUserData(OWNED, this)
    }

    override fun processStarting(
        executorId: String,
        environment: ExecutionEnvironment,
        handler: ProcessHandler,
    ) {
        if (environment.getUserData(OWNED) !== this) return
        try {
            if (environment.getUserData(FAILED) == true) {
                if (!handler.isProcessTerminated) handler.destroyProcess()
                return
            }
            val descriptor = environment.contentToReuse
            if (descriptor == null) {
                if (!handler.isProcessTerminated) handler.destroyProcess()
                return
            }
            val child = DescriptorAffectedRunChild(descriptor, handler)
            if (presentation.attach(label, child)) installInitialOutput(handler)
        } finally {
            dispose()
        }
    }

    private fun installInitialOutput(handler: ProcessHandler) {
        if (initialOutput.isEmpty()) return
        handler.addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {
                event.processHandler.removeProcessListener(this)
                event.processHandler.notifyTextAvailable(initialOutput, ProcessOutputTypes.SYSTEM)
            }
        })
    }

    override fun processNotStarted(executorId: String, environment: ExecutionEnvironment) {
        if (environment.getUserData(OWNED) === this || matches(environment, marker)) dispose()
    }

    override fun processNotStarted(
        executorId: String,
        environment: ExecutionEnvironment,
        cause: Throwable,
    ) = processNotStarted(executorId, environment)

    override fun dispose() {
        if (disposed.compareAndSet(false, true)) connection.dispose()
    }

    companion object {
        private val MARKER = Key.create<Any>("affected.aggregate.execution.marker")
        private val OWNED = Key.create<AffectedExternalRunBinding>("affected.aggregate.execution.binding")
        private val FAILED = Key.create<Boolean>("affected.aggregate.execution.failed")

        fun open(
            project: Project,
            presentation: AffectedRunPresentation,
            label: String,
            initialOutput: String = "",
            enableHeadless: ((ExecutionEnvironment) -> Boolean)? = AffectedHeadlessExecution.enable,
            matches: (ExecutionEnvironment, Any) -> Boolean,
        ): AffectedExternalRunBinding? {
            val enable = enableHeadless ?: return null
            return AffectedExternalRunBinding(project, presentation, label, initialOutput, enable, matches)
        }

        fun isSupported(): Boolean = AffectedHeadlessExecution.enable != null

        fun matchesMarker(environment: ExecutionEnvironment, marker: Any): Boolean =
            (environment.runProfile as? UserDataHolder)?.getUserData(MARKER) === marker
    }
}

private class DescriptorAffectedRunChild(
    private val descriptor: RunContentDescriptor,
    private val handler: ProcessHandler,
) : AffectedRunChild {
    private val disposed = AtomicBoolean()

    override val component get() = descriptor.component
    override val preferredFocus get() = descriptor.preferredFocusComputable?.compute()

    override fun stop() {
        if (!handler.isProcessTerminated) handler.destroyProcess()
    }

    override fun dispose() {
        if (disposed.compareAndSet(false, true)) Disposer.dispose(descriptor)
    }
}

private object AffectedHeadlessExecution {
    val enable: ((ExecutionEnvironment) -> Boolean)? = runCatching {
        val setter = ExecutionEnvironment::class.java.getMethod("setHeadless")
        val getter = ExecutionEnvironment::class.java.getMethod("isHeadless")
        val action: (ExecutionEnvironment) -> Boolean = { environment ->
            runCatching {
                setter.invoke(environment)
                getter.invoke(environment) == true
            }.getOrDefault(false)
        }
        action
    }.getOrNull()
}
