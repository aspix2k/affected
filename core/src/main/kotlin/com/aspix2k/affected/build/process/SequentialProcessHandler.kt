package com.aspix2k.affected.build.process

import com.aspix2k.affected.AffectedOwnedSession
import com.aspix2k.affected.build.ExecutionRootGuard
import com.aspix2k.affected.build.executionRootGuard
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal class SequentialProcessHandler(
    workingDirectory: File,
    commands: List<CliStep>,
    unresolvedMessage: String = DEFAULT_UNRESOLVED_MESSAGE,
    continueAfterFailure: Boolean = false,
    executionRootGuard: ExecutionRootGuard = executionRootGuard(workingDirectory.toPath()),
    hooks: SequenceHooks = SequenceHooks(),
) : ProcessHandler(), AffectedOwnedSession {

    private val notified = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    val commandStarted: Boolean get() = started.get()

    private val sequence = CommandSequence(
        workingDirectory,
        commands,
        object : CommandSequenceListener {
            override fun onText(text: String, kind: OutputKind) {
                notifyTextAvailable(
                    text,
                    when (kind) {
                        OutputKind.STDOUT -> ProcessOutputTypes.STDOUT
                        OutputKind.STDERR -> ProcessOutputTypes.STDERR
                        OutputKind.SYSTEM -> ProcessOutputTypes.SYSTEM
                    },
                )
            }

            override fun onCommandStarted(command: CliCommand) = started.set(true)

            override fun onTerminated(exitCode: Int) = notifyProcessTerminated(exitCode)
        },
        ideProcessHost,
        unresolvedMessage,
        continueAfterFailure,
        executionRootGuard,
        hooks,
    )

    override fun startNotify() {
        if (!notified.compareAndSet(false, true)) return
        super.startNotify()
        sequence.start()
    }

    override fun destroyProcessImpl() {
        sequence.requestStop()
    }

    override fun detachProcessImpl() {
        sequence.requestStop()
    }

    override fun detachIsDefault(): Boolean = false

    override fun isActive(): Boolean = sequence.isActive

    override fun stopIfActive(): Boolean = sequence.requestStop()

    override fun getProcessInput(): OutputStream = sequence.processInput
}
