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

internal sealed interface CliStep {
    fun resolve(): CliCommand?
}

internal data class CliCommand(
    val title: String,
    val arguments: List<String>,
    val environment: Map<String, String> = emptyMap(),
    val continueOnFailure: Boolean = false,
) : CliStep {
    init {
        require(title.isNotBlank())
        require(arguments.isNotEmpty())
    }

    override fun resolve(): CliCommand = this
}

internal class DeferredCliCommand(
    private val title: String,
    private val environment: () -> Map<String, String>,
    private val arguments: () -> List<String>?,
) : CliStep {
    constructor(title: String, arguments: () -> List<String>?) : this(title, { emptyMap() }, arguments)

    init {
        require(title.isNotBlank())
    }

    override fun resolve(): CliCommand? = arguments()?.let { CliCommand(title, it, environment()) }
}

internal class SequentialProcessHandler(
    private val workingDirectory: File,
    private val commands: List<CliStep>,
    private val unresolvedMessage: String = DEFAULT_UNRESOLVED_MESSAGE,
    private val continueAfterFailure: Boolean = false,
) : ProcessHandler() {

    private val finished = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val lock = Any()
    private var next = 0
    private var recordedExitCode = 0

    @Volatile
    private var current: OSProcessHandler? = null

    @Volatile
    private var resolverThread: Thread? = null

    override fun startNotify() {
        super.startNotify()
        AppExecutorUtil.getAppExecutorService().execute(::startNext)
    }

    override fun destroyProcessImpl() {
        stopped.set(true)
        val handler = synchronized(lock) {
            resolverThread?.interrupt()
            current
        }
        handler?.destroyProcess() ?: finish(1)
    }

    override fun detachProcessImpl() {
        stopped.set(true)
        val handler = synchronized(lock) {
            resolverThread?.interrupt()
            current
        }
        handler?.detachProcess()
        notifyDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream = current?.processInput ?: OutputStream.nullOutputStream()

    private fun startNext() {
        if (stopped.get()) return finish(1)
        val step = synchronized(lock) {
            commands.getOrNull(next)?.also { next += 1 }
        } ?: return if (next == 0) {
            notifyTextAvailable(
                "$unresolvedMessage\n",
                ProcessOutputTypes.STDERR,
            )
            finish(1)
        } else {
            finish(synchronized(lock) { recordedExitCode })
        }

        val mayResolve = synchronized(lock) {
            if (stopped.get()) {
                false
            } else {
                resolverThread = Thread.currentThread()
                true
            }
        }
        if (!mayResolve) return finish(1)
        val command = runCatching(step::resolve).also {
            synchronized(lock) {
                if (resolverThread === Thread.currentThread()) resolverThread = null
            }
        }.getOrElse { error ->
            notifyTextAvailable(
                "Affected could not resolve the next command: ${error.message.orEmpty()}\n",
                ProcessOutputTypes.STDERR,
            )
            return finish(1)
        }
        if (command == null) {
            if (stopped.get()) return finish(1)
            AppExecutorUtil.getAppExecutorService().execute(::startNext)
            return
        }
        if (stopped.get()) return finish(1)

        notifyTextAvailable("\n> ${command.title}\n", ProcessOutputTypes.SYSTEM)
        val handler = runCatching {
            val arguments = command.arguments.toMutableList()
            arguments[0] = resolveExecutable(arguments[0])
            OSProcessHandler(
                GeneralCommandLine(arguments)
                    .withWorkDirectory(workingDirectory)
                    .withCharset(Charsets.UTF_8)
                    .withEnvironment(command.environment),
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
                when {
                    event.exitCode == 0 && !stopped.get() ->
                        AppExecutorUtil.getAppExecutorService().execute(::startNext)
                    shouldContinueAfterFailure(command, event.exitCode) -> {
                        synchronized(lock) {
                            if (recordedExitCode == 0) recordedExitCode = event.exitCode
                        }
                        AppExecutorUtil.getAppExecutorService().execute(::startNext)
                    }
                    else -> finish(event.exitCode.takeIf { it != 0 } ?: 1)
                }
            }
        })
        handler.startNotify()
    }

    private fun shouldContinueAfterFailure(command: CliCommand, exitCode: Int): Boolean =
        exitCode != 0 && !stopped.get() && (command.continueOnFailure || continueAfterFailure)

    private fun notifyDetached() {
        if (finished.compareAndSet(false, true)) notifyProcessDetached()
    }

    private fun finish(exitCode: Int) {
        if (finished.compareAndSet(false, true)) notifyProcessTerminated(exitCode)
    }
}
