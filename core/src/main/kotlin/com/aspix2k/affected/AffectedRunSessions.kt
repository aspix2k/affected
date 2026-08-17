package com.aspix2k.affected

import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

interface AffectedOwnedSession {
    fun isActive(): Boolean
    fun stopIfActive(): Boolean
}

@Service(Service.Level.PROJECT)
class AffectedRunSessions : Disposable {

    private val lock = Any()
    private val sessions = ConcurrentHashMap.newKeySet<AffectedOwnedSession>()
    private val sessionOwners = HashMap<AffectedOwnedSession, AffectedRunClaim>()
    private val runs = ConcurrentHashMap.newKeySet<AffectedRunClaim>()
    private var disposed = false

    internal fun claim(create: () -> AffectedRunClaim?): AffectedRunClaim? = synchronized(lock) {
        if (disposed) return null
        create()?.also { run ->
            run.bind(this)
            runs.add(run)
        }
    }

    internal fun complete(run: AffectedRunClaim, passed: Boolean): AffectedRunCompletion = synchronized(lock) {
        run.completeFrom(this, passed).also { completion ->
            if (completion.released) runs.remove(run)
        }
    }

    fun register(session: AffectedOwnedSession): Boolean {
        val owner = ActiveAffectedRun.current()?.run
        val rejected = synchronized(lock) {
            sessions.add(session)
            if (owner != null) sessionOwners[session] = owner
            disposed || owner?.isTerminationRequested() ?: runs.any(AffectedRunClaim::isCancellationRequested)
        }
        if (rejected) {
            session.stopIfActive()
        }
        synchronized(lock) {
            pruneSessions()
        }
        return !rejected
    }

    fun register(handler: ProcessHandler): Boolean = register(ProcessHandlerSession(handler))

    fun unregister(session: AffectedOwnedSession) {
        synchronized(lock) {
            sessions.remove(session)
            sessionOwners.remove(session)
        }
    }

    fun unregister(handler: ProcessHandler) {
        synchronized(lock) {
            val owned = sessions.filterIsInstance<ProcessHandlerSession>().filterTo(HashSet()) { it.owns(handler) }
            sessions.removeAll(owned)
            sessionOwners.keys.removeAll(owned)
        }
    }

    fun stopOwned(): Int {
        var stoppedRuns = 0
        val owned = synchronized(lock) {
            stoppedRuns = runs.count { it.stopIfActive() }
            sessions.toList()
        }
        val stopped = owned.count { it.stopIfActive() }
        return synchronized(lock) {
            runs.removeIf { !it.isActive() }
            pruneSessions()
            if (runs.isNotEmpty() || stoppedRuns > 0) stoppedRuns else stopped
        }
    }

    internal fun stopOwned(run: AffectedRunClaim): Int {
        val owned = synchronized(lock) {
            sessionOwners.filterValues { it === run }.keys.toList()
        }
        val stopped = owned.count { it.stopIfActive() }
        synchronized(lock) { pruneSessions() }
        return stopped
    }

    fun activeCount(): Int = synchronized(lock) {
        runs.removeIf { !it.isActive() }
        pruneSessions()
        runs.size.takeIf { it > 0 } ?: sessions.size
    }

    override fun dispose() {
        synchronized(lock) { disposed = true }
        stopOwned()
    }

    private fun pruneSessions() {
        sessions.removeIf { !it.isActive() }
        sessionOwners.keys.retainAll(sessions)
    }

    companion object {
        fun getInstance(project: Project): AffectedRunSessions =
            project.getService(AffectedRunSessions::class.java)
    }
}

internal suspend fun <T> withAffectedRun(
    run: AffectedRunClaim,
    presentation: AffectedRunPresentation? = null,
    block: suspend () -> T,
): T = withContext(AffectedRunContextElement(ActiveAffectedRunState(run, presentation))) { block() }

internal fun currentAffectedRunPresentation(): AffectedRunPresentation? =
    ActiveAffectedRun.current()?.presentation

private data class ActiveAffectedRunState(
    val run: AffectedRunClaim,
    val presentation: AffectedRunPresentation?,
)

private object ActiveAffectedRun {
    private val current = ThreadLocal<ActiveAffectedRunState?>()

    fun current(): ActiveAffectedRunState? = current.get()

    fun replace(run: ActiveAffectedRunState?): ActiveAffectedRunState? {
        val previous = current.get()
        current.set(run)
        return previous
    }
}

private class AffectedRunContextElement(
    private val run: ActiveAffectedRunState,
) : ThreadContextElement<ActiveAffectedRunState?>, AbstractCoroutineContextElement(Key) {
    override fun updateThreadContext(
        context: CoroutineContext,
    ): ActiveAffectedRunState? = ActiveAffectedRun.replace(run)

    override fun restoreThreadContext(context: CoroutineContext, oldState: ActiveAffectedRunState?) {
        ActiveAffectedRun.replace(oldState)
    }

    private companion object Key : CoroutineContext.Key<AffectedRunContextElement>
}

internal class OwnedProcessExecution(
    private val stopProcess: (ProcessHandler) -> Unit = { handler -> handler.destroyProcess() },
    private val onCancel: () -> Unit = {},
) : AffectedOwnedSession {
    private val lock = Any()
    private val completion = CompletableDeferred<Boolean>()
    private var handler: ProcessHandler? = null
    private var cancellationRequested = false
    private var terminalAcceptance: Boolean? = null
    private var finished = false

    fun bind(handler: ProcessHandler) {
        val stop = synchronized(lock) {
            if (this.handler != null) return
            this.handler = handler
            cancellationRequested || finished
        }
        if (stop && !handler.isProcessTerminated) runCatching { stopProcess(handler) }
    }

    fun isCancellationRequested(): Boolean = synchronized(lock) { cancellationRequested }

    fun seal(): Boolean = synchronized(lock) {
        terminalAcceptance ?: (!cancellationRequested).also { terminalAcceptance = it }
    }

    fun finish(action: (Boolean) -> Unit = {}): Boolean {
        var completed = false
        var accepted = false
        try {
            synchronized(lock) {
                if (finished) return false
                accepted = terminalAcceptance ?: (!cancellationRequested).also { terminalAcceptance = it }
                try {
                    action(accepted)
                } finally {
                    finished = true
                    completed = true
                }
            }
        } finally {
            if (completed) completion.complete(accepted)
        }
        return accepted
    }

    suspend fun awaitFinished(): Boolean = completion.await()

    override fun isActive(): Boolean = synchronized(lock) { !finished }

    override fun stopIfActive(): Boolean {
        val process = synchronized(lock) {
            if (finished || terminalAcceptance != null || cancellationRequested) return false
            cancellationRequested = true
            handler
        }
        runCatching(onCancel)
        if (process != null && !process.isProcessTerminated) runCatching { stopProcess(process) }
        return true
    }
}

internal class OwnedExternalTaskExecution(
    private val cancelTask: (ExternalSystemTaskId, () -> Unit, () -> Unit, () -> Unit) -> Boolean,
    private val onCancel: () -> Unit = {},
) : AffectedOwnedSession {

    constructor(
        cancelTask: (ExternalSystemTaskId) -> Boolean,
        onCancel: () -> Unit = {},
    ) : this({ id, _, _, _ -> cancelTask(id) }, onCancel)

    private enum class Phase {
        PENDING,
        LAUNCHING,
        BOUND,
        TERMINATED,
    }

    private val lock = Any()
    private val result = CompletableDeferred<Boolean>()
    private var phase = Phase.PENDING
    private var taskId: ExternalSystemTaskId? = null
    private var outcome: Boolean? = null
    private var cancelRequested = false
    private var cancellationScheduled = false
    private var endObserved = false

    val listener: ExternalSystemTaskNotificationListener = object : ExternalSystemTaskNotificationListener {
        override fun onStart(projectPath: String, id: ExternalSystemTaskId) {
            bind(id)
        }

        override fun onEnvironmentPrepared(id: ExternalSystemTaskId) {
            requestCancellation(id)
        }

        override fun onSuccess(projectPath: String, id: ExternalSystemTaskId) {
            recordOutcome(id, passed = true)
        }

        override fun onFailure(projectPath: String, id: ExternalSystemTaskId, exception: Exception) {
            recordOutcome(id, passed = false)
        }

        override fun onCancel(projectPath: String, id: ExternalSystemTaskId) {
            recordOutcome(id, passed = false)
        }

        override fun onEnd(projectPath: String, id: ExternalSystemTaskId) {
            end(id)
        }
    }

    val callback: TaskCallback = object : TaskCallback {
        override fun onSuccess() {
            finish(passed = true)
        }

        override fun onFailure() {
            finish(passed = false)
        }
    }

    fun isCancellationRequested(): Boolean = synchronized(lock) { cancelRequested }

    fun launch(block: (ExternalSystemTaskNotificationListener) -> Unit): Boolean {
        val launched = synchronized(lock) {
            if (phase == Phase.TERMINATED && cancelRequested) return@synchronized false
            check(phase == Phase.PENDING)
            if (cancelRequested) {
                phase = Phase.TERMINATED
                false
            } else {
                phase = Phase.LAUNCHING
                true
            }
        }
        if (!launched) {
            result.complete(false)
            return false
        }
        try {
            block(listener)
            launchReturned()
        } catch (error: Throwable) {
            launchFailed()
            throw error
        }
        return true
    }

    fun launchFailed() {
        var cancel: ExternalSystemTaskId? = null
        var notifyCancellation = false
        val complete = synchronized(lock) {
            when (phase) {
                Phase.BOUND -> {
                    if (!cancelRequested) {
                        cancelRequested = true
                        notifyCancellation = true
                        cancel = taskId
                    }
                    false
                }
                Phase.PENDING, Phase.LAUNCHING -> {
                    phase = Phase.TERMINATED
                    true
                }
                Phase.TERMINATED -> false
            }
        }
        if (notifyCancellation) runCatching(onCancel)
        cancel?.let(::requestCancellation)
        if (complete) result.complete(false)
    }

    suspend fun awaitResult(): Boolean = result.await()

    override fun isActive(): Boolean = synchronized(lock) {
        phase != Phase.TERMINATED
    }

    override fun stopIfActive(): Boolean {
        var complete = false
        val cancellation = synchronized(lock) {
            if (phase == Phase.TERMINATED || cancelRequested) return false
            cancelRequested = true
            if (phase == Phase.PENDING) {
                phase = Phase.TERMINATED
                complete = true
            }
            taskId
        }
        runCatching(onCancel)
        cancellation?.let(::requestCancellation)
        if (complete) result.complete(false)
        return true
    }

    private fun bind(id: ExternalSystemTaskId) {
        val cancel = synchronized(lock) {
            if (phase != Phase.LAUNCHING) return
            phase = Phase.BOUND
            taskId = id
            cancelRequested
        }
        if (cancel) requestCancellation(id)
    }

    private fun requestCancellation(id: ExternalSystemTaskId) {
        val shouldCancel = synchronized(lock) {
            val eligible = phase == Phase.BOUND && taskId == id && cancelRequested && !cancellationScheduled
            if (eligible) cancellationScheduled = true
            eligible
        }
        if (!shouldCancel) {
            return
        }
        val scheduled = runCatching {
            cancelTask(
                id,
                { terminateCancellation(id) },
                { cancellationMonitoringStopped(id) },
                { cancellationAttemptsExhausted(id) },
            )
        }.getOrDefault(false)
        if (!scheduled) {
            synchronized(lock) {
                if (phase == Phase.BOUND && taskId == id) cancellationScheduled = false
            }
        }
    }

    private fun recordOutcome(id: ExternalSystemTaskId, passed: Boolean) {
        synchronized(lock) {
            if (phase == Phase.BOUND && taskId == id) outcome = passed
        }
    }

    private fun end(id: ExternalSystemTaskId) {
        var cancelled = false
        val passed = synchronized(lock) {
            if (phase != Phase.BOUND || taskId != id) return
            if (cancelRequested) {
                cancelled = true
                endObserved = true
                return@synchronized false
            }
            phase = Phase.TERMINATED
            outcome == true
        }
        if (cancelled) {
            requestCancellation(id)
            return
        }
        result.complete(passed)
    }

    private fun terminateCancellation(id: ExternalSystemTaskId) {
        synchronized(lock) {
            if (phase != Phase.BOUND || taskId != id || !cancelRequested) return
            phase = Phase.TERMINATED
        }
        result.complete(false)
    }

    private fun cancellationMonitoringStopped(id: ExternalSystemTaskId) {
        val retry = synchronized(lock) {
            if (phase != Phase.BOUND || taskId != id || !cancelRequested) return
            cancellationScheduled = false
            endObserved
        }
        if (retry) {
            requestCancellation(id)
        }
    }

    private fun cancellationAttemptsExhausted(id: ExternalSystemTaskId) {
        synchronized(lock) {
            if (phase == Phase.BOUND && taskId == id && cancelRequested) {
                cancellationScheduled = false
            }
        }
    }

    private fun launchReturned() {
        val passed = synchronized(lock) {
            when (phase) {
                Phase.PENDING, Phase.LAUNCHING -> {
                    phase = Phase.TERMINATED
                    outcome == true && !cancelRequested
                }
                Phase.BOUND -> {
                    return
                }
                Phase.TERMINATED -> return
            }
        }
        result.complete(passed)
    }

    private fun finish(passed: Boolean) {
        val completed = synchronized(lock) {
            when (phase) {
                Phase.BOUND -> {
                    outcome = passed
                    return
                }
                Phase.PENDING, Phase.LAUNCHING -> {
                    phase = Phase.TERMINATED
                    passed && !cancelRequested
                }
                Phase.TERMINATED -> return
            }
        }
        result.complete(completed)
    }
}

internal suspend fun runOwnedExternalTask(
    sessions: AffectedRunSessions,
    execution: OwnedExternalTaskExecution,
    launch: (ExternalSystemTaskNotificationListener) -> Unit,
): Boolean = runPreparedOwnedExternalTask(sessions, execution, prepare = {}, launch)

internal suspend fun runPreparedOwnedExternalTask(
    sessions: AffectedRunSessions,
    execution: OwnedExternalTaskExecution,
    prepare: suspend () -> Unit,
    launch: (ExternalSystemTaskNotificationListener) -> Unit,
): Boolean {
    if (!sessions.register(execution)) return false
    try {
        currentCoroutineContext().ensureActive()
        prepare()
        currentCoroutineContext().ensureActive()
        return awaitOwnedExternalTask(execution, launch)
    } catch (cancelled: CancellationException) {
        execution.stopIfActive()
        withContext(NonCancellable) { execution.awaitResult() }
        throw cancelled
    } finally {
        sessions.unregister(execution)
    }
}

private suspend fun awaitOwnedExternalTask(
    execution: OwnedExternalTaskExecution,
    launch: (ExternalSystemTaskNotificationListener) -> Unit,
): Boolean = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { execution.stopIfActive() }
    try {
        AppExecutorUtil.getAppExecutorService().execute {
            runCatching { execution.launch(launch) }
            val passed = runBlocking { execution.awaitResult() }
            if (continuation.isActive) continuation.resume(passed)
        }
    } catch (_: RuntimeException) {
        execution.launchFailed()
        if (continuation.isActive) continuation.resume(false)
    }
}

internal fun monitorGradleCancellation(
    cancel: () -> Boolean,
    terminated: () -> Boolean,
    onTerminated: () -> Unit,
    onMonitoringStopped: () -> Unit = {},
    onCancelAttemptsExhausted: () -> Unit = {},
    schedule: (Long, () -> Unit) -> Boolean = ::scheduleGradleCancellation,
): Boolean {
    val completed = AtomicBoolean()
    val attemptsExhausted = AtomicBoolean()

    fun enqueue(index: Int): Boolean {
        val delay = if (index == 0) {
            0L
        } else {
            minOf(
                GRADLE_CANCEL_MAX_DELAY_MILLIS,
                GRADLE_CANCEL_INITIAL_DELAY_MILLIS shl (index - 1).coerceAtMost(GRADLE_CANCEL_MAX_SHIFT),
            )
        }
        return schedule(delay) {
            if (completed.get()) {
                return@schedule
            }
            if (runCatching(terminated).getOrDefault(false)) {
                if (completed.compareAndSet(false, true)) {
                    onTerminated()
                }
                return@schedule
            }
            if (index < GRADLE_CANCEL_MAX_ATTEMPTS) {
                runCatching(cancel)
            }
            val finished = runCatching(terminated).getOrDefault(false)
            if (finished && completed.compareAndSet(false, true)) {
                onTerminated()
            } else {
                val nextIndex = minOf(index + 1, GRADLE_CANCEL_MAX_ATTEMPTS)
                if (
                    nextIndex == GRADLE_CANCEL_MAX_ATTEMPTS &&
                    attemptsExhausted.compareAndSet(false, true)
                ) {
                    onCancelAttemptsExhausted()
                }
                val enqueued = enqueue(nextIndex)
                if (!enqueued) {
                    onMonitoringStopped()
                }
            }
        }
    }

    return enqueue(0)
}

private fun scheduleGradleCancellation(delayMillis: Long, action: () -> Unit): Boolean = runCatching {
    AppExecutorUtil.getAppScheduledExecutorService().schedule(action, delayMillis, TimeUnit.MILLISECONDS)
}.isSuccess

private const val GRADLE_CANCEL_MAX_ATTEMPTS = 64
private const val GRADLE_CANCEL_INITIAL_DELAY_MILLIS = 10L
private const val GRADLE_CANCEL_MAX_DELAY_MILLIS = 1_000L
private const val GRADLE_CANCEL_MAX_SHIFT = 20

private class ProcessHandlerSession(
    private val handler: ProcessHandler,
) : AffectedOwnedSession {
    fun owns(candidate: ProcessHandler): Boolean = handler === candidate

    override fun isActive(): Boolean = !handler.isProcessTerminated

    override fun stopIfActive(): Boolean {
        if (!isActive()) return false
        handler.destroyProcess()
        return true
    }
}
