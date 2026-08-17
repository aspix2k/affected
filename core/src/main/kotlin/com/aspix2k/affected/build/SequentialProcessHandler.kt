package com.aspix2k.affected.build

import com.aspix2k.affected.AffectedOwnedSession
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
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
    val ownedTemporaryDirectories: List<Path> = emptyList(),
) : CliStep {
    init {
        require(title.isNotBlank())
        require(arguments.isNotEmpty())
        require(ownedTemporaryDirectories.all(::isOwnedTemporaryDirectory))
    }

    override fun resolve(): CliCommand = this
}

internal class DeferredCliCommand private constructor(
    private val command: () -> CliCommand?,
) : CliStep {
    constructor(
        title: String,
        environment: () -> Map<String, String>,
        arguments: () -> List<String>?,
    ) : this({ arguments()?.let { CliCommand(title, it, environment()) } })

    constructor(title: String, arguments: () -> List<String>?) : this(title, { emptyMap() }, arguments)

    override fun resolve(): CliCommand? = command()

    companion object {
        fun command(resolve: () -> CliCommand?): DeferredCliCommand = DeferredCliCommand(resolve)
    }
}

internal class SequentialProcessHandler(
    private val workingDirectory: File,
    private val commands: List<CliStep>,
    private val unresolvedMessage: String = DEFAULT_UNRESOLVED_MESSAGE,
    private val continueAfterFailure: Boolean = false,
    private val executionRootGuard: ExecutionRootGuard = executionRootGuard(workingDirectory.toPath()),
    private val processHandlerFactory: (GeneralCommandLine) -> OSProcessHandler = { OSProcessHandler(it) },
    private val ownedTemporaryDirectoryCleanup: (Path) -> Boolean = ::deleteOwnedTemporaryDirectory,
    private val afterInitialProcessTermination: () -> Unit = {},
    private val processTreeTerminationFactory: (ProcessHandle, () -> Unit) -> ProcessTreeTermination =
        { root, afterInitialPass -> ProcessTreeTermination(root, afterInitialPass = afterInitialPass) },
) : ProcessHandler(), AffectedOwnedSession {

    private val finished = AtomicBoolean(false)
    private val notified = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val lock = Any()
    private var next = 0
    private var recordedExitCode = 0
    private var activeCommand: CliCommand? = null
    private var lifecycleActive = false
    private var processTreeTermination: ProcessTreeTermination? = null
    private var terminalDecision = false

    @Volatile
    private var current: OSProcessHandler? = null

    @Volatile
    private var resolverThread: Thread? = null

    override fun startNotify() {
        if (!notified.compareAndSet(false, true)) return
        super.startNotify()
        AppExecutorUtil.getAppExecutorService().execute(::startNext)
    }

    override fun destroyProcessImpl() {
        requestStop()
    }

    override fun isActive(): Boolean = !finished.get()

    override fun stopIfActive(): Boolean = requestStop()

    private fun requestStop(): Boolean {
        var handler: OSProcessHandler? = null
        var hasActiveLifecycle = false
        var termination: ProcessTreeTermination? = null
        var initiate = false
        var pending = emptyList<CliCommand>()
        synchronized(lock) {
            if (!finished.get() && !terminalDecision && !stopped.get()) {
                handler = current
                if (handler != null) termination = checkNotNull(processTreeTermination)
                stopped.set(true)
                initiate = true
            }
            resolverThread?.interrupt()
            hasActiveLifecycle = resolverThread != null || activeCommand != null || lifecycleActive
            if (initiate && handler == null && !hasActiveLifecycle) {
                lifecycleActive = true
                pending = commands.drop(next).filterIsInstance<CliCommand>()
                next = commands.size
            }
        }
        if (!initiate) return false
        when {
            handler != null -> termination!!.request()
            !hasActiveLifecycle -> {
                pending.forEach(::cleanup)
                endLifecycle()
                finish(1)
            }
        }
        return true
    }

    override fun detachProcessImpl() {
        requestStop()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream = current?.processInput ?: OutputStream.nullOutputStream()

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
            notifyTextAvailable(
                "Affected could not resolve the next command: ${error.message.orEmpty()}\n",
                ProcessOutputTypes.STDERR,
            )
            return finish(1)
        }
        if (command == null) {
            endLifecycle()
            if (stopped.get()) return finish(1)
            AppExecutorUtil.getAppExecutorService().execute(::startNext)
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
            notifyTextAvailable("$unresolvedMessage\n", ProcessOutputTypes.STDERR)
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

        notifyTextAvailable("\n> ${command.title}\n", ProcessOutputTypes.SYSTEM)
        val commandLine = runCatching {
            val arguments = command.arguments.toMutableList()
            arguments[0] = resolveExecutable(arguments[0])
            GeneralCommandLine(arguments)
                .withWorkDirectory(workingDirectory)
                .withCharset(Charsets.UTF_8)
                .withEnvironment(command.environment)
        }.getOrElse { error ->
            notifyTextAvailable(
                "Affected could not start ${command.title}: ${error.message.orEmpty()}\n",
                ProcessOutputTypes.STDERR,
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
        val handler = runCatching { processHandlerFactory(commandLine) }.getOrElse { error ->
            notifyTextAvailable(
                "Affected could not start ${command.title}: ${error.message.orEmpty()}\n",
                ProcessOutputTypes.STDERR,
            )
            cleanup(command)
            release(command)
            finish(1)
            return
        }
        val termination = processTreeTerminationFactory(
            handler.process.toHandle(),
            afterInitialProcessTermination,
        )

        val shouldStart = synchronized(lock) {
            processTreeTermination = termination
            terminalDecision = false
            if (stopped.get()) {
                false
            } else {
                current = handler
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
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                notifyTextAvailable(event.text, outputType)
            }

            override fun processTerminated(event: ProcessEvent) {
                val cancellationObserved = synchronized(lock) {
                    val cancelled = stopped.get() || termination.isRequested
                    terminalDecision = true
                    if (current === handler) current = null
                    if (!cancelled && processTreeTermination === termination) processTreeTermination = null
                    cancelled
                }
                if (!cancellationObserved) termination.close()
                val terminated = !cancellationObserved || awaitTerminatingProcesses()
                if (!terminated) {
                    notifyTextAvailable(
                        "Affected could not terminate every child process before cleanup.\n",
                        ProcessOutputTypes.STDERR,
                    )
                }
                val cleaned = terminated && cleanup(command)
                release(command)
                val exitCode = if (cleaned) event.exitCode else event.exitCode.takeIf { it != 0 } ?: 1
                when {
                    exitCode == 0 && !stopped.get() -> {
                        reopenCancellation()
                        AppExecutorUtil.getAppExecutorService().execute(::startNext)
                    }
                    shouldContinueAfterFailure(command, exitCode) -> {
                        synchronized(lock) {
                            if (recordedExitCode == 0) recordedExitCode = exitCode
                        }
                        reopenCancellation()
                        AppExecutorUtil.getAppExecutorService().execute(::startNext)
                    }
                    else -> finish(exitCode.takeIf { it != 0 } ?: 1)
                }
            }
        })
        handler.startNotify()
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
        notifyTextAvailable(
            "Affected refused to start commands because the planned working directory $failure. " +
                "Refresh the project model and run again.\n",
            ProcessOutputTypes.STDERR,
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

    private fun reopenCancellation() {
        synchronized(lock) { terminalDecision = false }
    }

    private fun cleanup(command: CliCommand): Boolean {
        val interrupted = Thread.interrupted()
        return try {
            val failed = command.ownedTemporaryDirectories.filterNot(ownedTemporaryDirectoryCleanup)
            if (failed.isEmpty()) {
                true
            } else {
                notifyTextAvailable(
                    "Affected could not remove its temporary output: ${failed.joinToString()}\n",
                    ProcessOutputTypes.STDERR,
                )
                false
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun awaitTerminatingProcesses(): Boolean {
        val termination = synchronized(lock) { processTreeTermination } ?: return true
        val terminated = termination.await()
        if (!terminated) return false
        synchronized(lock) {
            if (processTreeTermination === termination) processTreeTermination = null
        }
        return true
    }

    private fun finish(exitCode: Int) {
        if (finished.compareAndSet(false, true)) notifyProcessTerminated(exitCode)
    }
}

private fun isOwnedTemporaryDirectory(path: Path): Boolean = runCatching {
    val normalized = path.toAbsolutePath().normalize()
    normalized == path &&
        normalized.fileName.toString().startsWith(OWNED_TEMPORARY_PREFIX) &&
        Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) &&
        !Files.isSymbolicLink(normalized) &&
        normalized.toRealPath().startsWith(temporaryRoot())
}.getOrDefault(false)

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

                override fun postVisitDirectory(directory: Path, error: java.io.IOException?): FileVisitResult {
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

private fun temporaryRoot(): Path = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()

private const val OWNED_TEMPORARY_PREFIX = "affected-"
private const val CLEANUP_ATTEMPTS = 3
private const val CLEANUP_BACKOFF_MILLIS = 50L
private const val MAX_CLEANUP_ENTRIES = 100_000
