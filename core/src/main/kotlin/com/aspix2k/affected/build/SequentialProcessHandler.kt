package com.aspix2k.affected.build

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal const val DEFAULT_UNRESOLVED_MESSAGE =
    "Affected could not resolve the planned modules. Refresh the project model and run again."

internal data class CliCommand(
    val title: String,
    val arguments: List<String>,
) {
    init {
        require(title.isNotBlank())
        require(arguments.isNotEmpty())
    }
}

internal class SequentialProcessHandler(
    private val workingDirectory: File,
    private val commands: List<CliCommand>,
    private val unresolvedMessage: String = DEFAULT_UNRESOLVED_MESSAGE,
) : ProcessHandler() {

    private val finished = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val lock = Any()
    private var next = 0

    @Volatile
    private var current: OSProcessHandler? = null

    override fun startNotify() {
        super.startNotify()
        AppExecutorUtil.getAppExecutorService().execute(::startNext)
    }

    override fun destroyProcessImpl() {
        stopped.set(true)
        val handler = synchronized(lock) { current }
        handler?.destroyProcess() ?: finish(1)
    }

    override fun detachProcessImpl() {
        stopped.set(true)
        val handler = synchronized(lock) { current }
        handler?.detachProcess()
        notifyDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream = current?.processInput ?: OutputStream.nullOutputStream()

    private fun startNext() {
        if (stopped.get()) return finish(1)
        val command = synchronized(lock) {
            commands.getOrNull(next)?.also { next += 1 }
        } ?: return if (next == 0) {
            notifyTextAvailable(
                "$unresolvedMessage\n",
                ProcessOutputTypes.STDERR,
            )
            finish(1)
        } else {
            finish(0)
        }

        notifyTextAvailable("\n> ${command.title}\n", ProcessOutputTypes.SYSTEM)
        val handler = runCatching {
            OSProcessHandler(
                GeneralCommandLine(command.arguments)
                    .withWorkDirectory(workingDirectory)
                    .withCharset(Charsets.UTF_8),
            )
        }.getOrElse { error ->
            notifyTextAvailable(
                "Affected could not start ${command.title}: ${error.message.orEmpty()}\n",
                ProcessOutputTypes.STDERR,
            )
            return finish(1)
        }

        val shouldStart = synchronized(lock) {
            if (stopped.get()) {
                false
            } else {
                current = handler
                true
            }
        }
        if (!shouldStart) {
            handler.destroyProcess()
            return finish(1)
        }
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                notifyTextAvailable(event.text, outputType)
            }

            override fun processTerminated(event: ProcessEvent) {
                synchronized(lock) {
                    if (current === handler) current = null
                }
                if (event.exitCode == 0 && !stopped.get()) {
                    AppExecutorUtil.getAppExecutorService().execute(::startNext)
                } else {
                    finish(event.exitCode.takeIf { it != 0 } ?: 1)
                }
            }
        })
        handler.startNotify()
    }

    private fun notifyDetached() {
        if (finished.compareAndSet(false, true)) notifyProcessDetached()
    }

    private fun finish(exitCode: Int) {
        if (finished.compareAndSet(false, true)) notifyProcessTerminated(exitCode)
    }
}
