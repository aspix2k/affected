package com.aspix2k.affected.build.process

import com.aspix2k.affected.build.ExecutionRootGuard
import com.aspix2k.affected.build.executionRootGuard
import com.aspix2k.affected.build.resolveExecutable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal const val DEFAULT_UNRESOLVED_MESSAGE =
    "Affected could not resolve the planned modules. Refresh the project model and run again."

internal enum class OutputKind { STDOUT, STDERR, SYSTEM }

internal interface CommandSequenceListener {
    fun onText(text: String, kind: OutputKind)

    fun onCommandStarted(command: CliCommand) = Unit

    fun onCommandFinished(command: CliCommand, exitCode: Int) = Unit

    fun onTerminated(exitCode: Int)
}

internal class RunningCommand(
    val process: Process,
    val termination: ProcessTermination,
    val start: () -> Unit,
)

internal class SequenceHooks(
    val processFactory: ((ProcessBuilder, () -> Unit) -> RunningCommand)? = null,
    val ownedTemporaryDirectoryCleanup: (Path) -> Boolean = ::deleteOwnedTemporaryDirectory,
    val afterInitialProcessTermination: () -> Unit = {},
    val beforeHelperLaunch: () -> Unit = {},
)

internal class CommandSequence(
    private val workingDirectory: File,
    private val commands: List<CliStep>,
    private val listener: CommandSequenceListener,
    private val host: ProcessHost,
    private val unresolvedMessage: String = DEFAULT_UNRESOLVED_MESSAGE,
    private val continueAfterFailure: Boolean = false,
    private val executionRootGuard: ExecutionRootGuard = executionRootGuard(workingDirectory.toPath()),
    private val hooks: SequenceHooks = SequenceHooks(),
) {

    private val finished = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val lock = Any()
    private val executor = ownedExecutor()
    private var next = 0
    private var recordedExitCode = 0
    private var activeCommand: CliCommand? = null
    private var lifecycleActive = false
    private var processTermination: ProcessTermination? = null
    private var terminalDecision = false
    private var pendingStop = false

    @Volatile
    private var current: Process? = null

    @Volatile
    private var resolverThread: Thread? = null

    val isActive: Boolean get() = !finished.get()

    val processInput: OutputStream get() = current?.outputStream ?: OutputStream.nullOutputStream()

    fun start() {
        if (started.compareAndSet(false, true)) submit(::startNext)
    }

    fun requestStop(): Boolean {
        var process: Process? = null
        var hasActiveLifecycle = false
        var termination: ProcessTermination? = null
        var initiate = false
        var pending = emptyList<CliCommand>()
        synchronized(lock) {
            if (deferStop()) return true
            if (!finished.get() && !terminalDecision && !stopped.get()) {
                process = current
                if (process != null) termination = checkNotNull(processTermination)
                stopped.set(true)
                initiate = true
            }
            resolverThread?.interrupt()
            hasActiveLifecycle = resolverThread != null || activeCommand != null || lifecycleActive
            if (initiate && process == null && !hasActiveLifecycle) {
                lifecycleActive = true
                pending = commands.drop(next).filterIsInstance<CliCommand>()
                next = commands.size
            }
        }
        if (!initiate) return false
        val activeTermination = termination
        when {
            activeTermination != null -> activeTermination.request()
            !hasActiveLifecycle -> {
                pending.forEach(::cleanup)
                endLifecycle()
                finish(1)
            }
        }
        return true
    }

    private fun deferStop(): Boolean {
        if (pendingStop || !terminalDecision || next >= commands.size) return false
        pendingStop = !finished.get() && !stopped.get()
        return pendingStop
    }

    private fun startNext() {
        if (!beginLifecycle()) return
        executionRootGuard.validationFailure()?.let { return failExecutionRoot(it) }
        val step = nextStep() ?: return finishWithoutStep()
        resolve(step)
    }

    private fun resolve(step: CliStep) {
        val mayResolve = synchronized(lock) {
            if (stopped.get()) {
                false
            } else {
                resolverThread = Thread.currentThread()
                true
            }
        }
        if (!mayResolve) {
            endLifecycle()
            return finish(1)
        }
        val resolved = executionRootGuard.withResolverContext { runCatching(step::resolve) }
        val command = resolved.getOrNull()
        synchronized(lock) {
            if (resolverThread === Thread.currentThread()) {
                if (resolved.isSuccess && command != null) activeCommand = command
                resolverThread = null
                if (command != null) lifecycleActive = false
            }
        }
        resolved.exceptionOrNull()?.let { error ->
            endLifecycle()
            listener.onText(
                "Affected could not resolve the next command: ${error.message.orEmpty()}\n",
                OutputKind.STDERR,
            )
            return finish(1)
        }
        if (command == null) {
            endLifecycle()
            if (stopped.get()) return finish(1)
            submit(::startNext)
            return
        }
        start(command)
    }

    private fun beginLifecycle(): Boolean = synchronized(lock) {
        if (stopped.get()) false else true.also { lifecycleActive = it }
    }

    private fun nextStep(): CliStep? = synchronized(lock) {
        commands.getOrNull(next)?.also { next += 1 }
    }

    private fun finishWithoutStep() {
        endLifecycle()
        if (next == 0) {
            listener.onText("$unresolvedMessage\n", OutputKind.STDERR)
            finish(1)
        } else {
            finish(synchronized(lock) { recordedExitCode })
        }
    }

    private fun start(command: CliCommand) {
        if (stopped.get()) {
            cleanup(command)
            release(command)
            finish(1)
            return
        }
        executionRootGuard.validationFailure()?.let { failure ->
            failExecutionRoot(failure, command)
            return
        }

        listener.onText("\n> ${command.title}\n", OutputKind.SYSTEM)
        listener.onCommandStarted(command)
        val target = runCatching {
            val arguments = command.arguments.toMutableList()
            arguments[0] = resolveExecutable(arguments[0])
            host.targetProcess(arguments, workingDirectory, command.environment)
        }.getOrElse { error ->
            listener.onText(
                "Affected could not start ${command.title}: ${error.message.orEmpty()}\n",
                OutputKind.STDERR,
            )
            cleanup(command)
            release(command)
            finish(1)
            return
        }
        executionRootGuard.validationFailure()?.let { failure ->
            failExecutionRoot(failure, command)
            return
        }
        val launched = runCatching { launch(target) }.getOrElse { error ->
            listener.onText(
                "Affected could not start ${command.title}: ${error.message.orEmpty()}\n",
                OutputKind.STDERR,
            )
            cleanup(command)
            release(command)
            finish(1)
            return
        }
        val process = launched.process
        val termination = launched.termination

        val shouldStart = synchronized(lock) {
            processTermination = termination
            terminalDecision = false
            if (stopped.get()) {
                false
            } else {
                current = process
                true
            }
        }
        if (!shouldStart) {
            termination.request()
            if (awaitTerminatingProcesses()) cleanup(command)
            release(command)
            finish(1)
            return
        }
        ProcessOutputPump(process, executor, listener::onText) { processExitCode ->
            processTerminated(command, process, termination, processExitCode)
        }.start()
        runCatching(launched.start).onFailure { error ->
            listener.onText(
                "Affected could not start ${command.title}: ${error.message.orEmpty()}\n",
                OutputKind.STDERR,
            )
            termination.request()
        }
    }

    private fun processTerminated(
        command: CliCommand,
        process: Process,
        termination: ProcessTermination,
        processExitCode: Int,
    ) {
        val terminated = settleTermination(process, termination)
        val cleaned = terminated && cleanup(command)
        release(command)
        val exitCode = if (cleaned && !termination.isRequested) processExitCode else failureCode(processExitCode)
        listener.onCommandFinished(command, exitCode)
        when {
            exitCode == 0 && !stopped.get() -> continueSequence(exitCode)
            shouldContinueAfterFailure(command, exitCode) -> {
                synchronized(lock) {
                    if (recordedExitCode == 0) recordedExitCode = exitCode
                }
                continueSequence(exitCode)
            }
            else -> finish(failureCode(exitCode))
        }
    }

    private fun settleTermination(process: Process, termination: ProcessTermination): Boolean {
        val cancellationObserved = synchronized(lock) {
            val cancelled = stopped.get() || termination.isRequested
            terminalDecision = true
            if (current === process) current = null
            if (!cancelled && processTermination === termination) processTermination = null
            cancelled
        }
        val terminated = if (cancellationObserved) awaitTerminatingProcesses() else termination.close()
        if (!terminated) {
            listener.onText(
                "Affected could not terminate every child process before cleanup.\n",
                OutputKind.STDERR,
            )
        }
        return terminated
    }

    private fun launch(target: ProcessBuilder): RunningCommand {
        hooks.processFactory?.let { return it(target, hooks.afterInitialProcessTermination) }
        val contained = ContainedProcess.prepare(
            target,
            host.supervisorRuntime(),
            afterTerminationProof = hooks.afterInitialProcessTermination,
            validateBeforeHelperLaunch = {
                hooks.beforeHelperLaunch()
                executionRootGuard.validationFailure()
            },
        )
        return RunningCommand(contained.process, contained, contained::start)
    }

    private fun shouldContinueAfterFailure(command: CliCommand, exitCode: Int): Boolean =
        exitCode != 0 && !stopped.get() && (command.continueOnFailure || continueAfterFailure)

    private fun failExecutionRoot(failure: String, currentCommand: CliCommand? = null) {
        val pending = synchronized(lock) {
            lifecycleActive = true
            commands.drop(next).filterIsInstance<CliCommand>().also { next = commands.size }
        }
        currentCommand?.let(::cleanup)
        pending.forEach(::cleanup)
        synchronized(lock) {
            if (activeCommand === currentCommand) activeCommand = null
            lifecycleActive = false
        }
        listener.onText(
            "Affected refused to start commands because the planned working directory $failure. " +
                "Refresh the project model and run again.\n",
            OutputKind.STDERR,
        )
        finish(1)
    }

    private fun release(command: CliCommand) {
        synchronized(lock) {
            if (activeCommand === command) activeCommand = null
        }
    }

    private fun endLifecycle() {
        synchronized(lock) { lifecycleActive = false }
    }

    private fun continueSequence(exitCode: Int) {
        var skipped = emptyList<CliCommand>()
        val stopPending = synchronized(lock) {
            terminalDecision = false
            pendingStop.also { pending ->
                if (pending) {
                    stopped.set(true)
                    skipped = commands.drop(next).filterIsInstance<CliCommand>()
                    next = commands.size
                }
            }
        }
        skipped.forEach(::cleanup)
        if (stopPending) {
            finish(failureCode(exitCode))
        } else {
            submit(::startNext)
        }
    }

    private fun cleanup(command: CliCommand): Boolean {
        val interrupted = Thread.interrupted()
        return try {
            val failed = command.ownedTemporaryDirectories.filterNot(hooks.ownedTemporaryDirectoryCleanup)
            if (failed.isEmpty()) {
                true
            } else {
                listener.onText(
                    "Affected could not remove its temporary output: ${failed.joinToString()}\n",
                    OutputKind.STDERR,
                )
                false
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun awaitTerminatingProcesses(): Boolean {
        val termination = synchronized(lock) { processTermination } ?: return true
        val terminated = termination.await()
        if (!terminated) return false
        synchronized(lock) {
            if (processTermination === termination) processTermination = null
        }
        return true
    }

    private fun finish(exitCode: Int) {
        if (!finished.compareAndSet(false, true)) return
        try {
            listener.onTerminated(exitCode)
        } finally {
            executor.shutdown()
        }
    }

    private fun submit(task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            check(finished.get()) { "The command sequence executor rejected a task before the sequence finished" }
        }
    }
}

private fun failureCode(exitCode: Int): Int = exitCode.takeIf { it != 0 } ?: 1

private fun ownedExecutor(): ThreadPoolExecutor {
    val index = AtomicInteger()
    return ThreadPoolExecutor(
        SEQUENCE_WORKERS,
        SEQUENCE_WORKERS,
        WORKER_IDLE_SECONDS,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
    ) { task ->
        Thread(task, "affected-command-sequence-${index.incrementAndGet()}").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
}

private fun deleteOwnedTemporaryDirectory(path: Path): Boolean {
    repeat(CLEANUP_ATTEMPTS) { attempt ->
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        if (Files.isSymbolicLink(path) || !isOwnedTemporaryDirectory(path)) return false
        var entries = 0
        runCatching {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                    check(++entries <= MAX_CLEANUP_ENTRIES)
                    check(!Thread.currentThread().isInterrupted)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    check(++entries <= MAX_CLEANUP_ENTRIES)
                    check(!Thread.currentThread().isInterrupted)
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            })
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        if (attempt + 1 < CLEANUP_ATTEMPTS) {
            try {
                Thread.sleep(CLEANUP_BACKOFF_MILLIS shl attempt)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }
    return false
}

private const val SEQUENCE_WORKERS = 8
private const val WORKER_IDLE_SECONDS = 1L
private const val CLEANUP_ATTEMPTS = 3
private const val CLEANUP_BACKOFF_MILLIS = 50L
private const val MAX_CLEANUP_ENTRIES = 100_000
