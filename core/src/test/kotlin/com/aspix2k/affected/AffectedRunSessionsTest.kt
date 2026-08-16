package com.aspix2k.affected

import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AffectedRunSessionsTest {

    @Test
    fun `stop terminates only registered active sessions`() {
        val sessions = AffectedRunSessions()
        val owned = RecordingSession(active = true)
        val finished = RecordingSession(active = false)
        val foreign = RecordingSession(active = true)

        sessions.register(owned)
        sessions.register(finished)

        assertEquals(1, sessions.activeCount())
        assertEquals(1, sessions.stopOwned())
        assertEquals(true, owned.stopped)
        assertEquals(false, finished.stopped)
        assertEquals(false, foreign.stopped)
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `cancellation before external launch prevents the task from starting`() = runBlocking {
        val sessions = AffectedRunSessions()
        val events = mutableListOf<String>()
        val execution = OwnedExternalTaskExecution(
            cancelTask = {
                events += "task"
                true
            },
            onCancel = { events += "collector" },
        )
        sessions.register(execution)

        assertEquals(1, sessions.stopOwned())
        assertFalse(execution.launch { events += "launch" })
        assertFalse(execution.awaitResult())
        assertEquals(listOf("collector"), events)
    }

    @Test
    fun `cancellation while launch is binding stops the future exact task`() = runBlocking {
        val sessions = AffectedRunSessions()
        val cancelled = mutableListOf<ExternalSystemTaskId>()
        val cancellation = RecordingCancellation {
            cancelled += it
            true
        }
        val execution = OwnedExternalTaskExecution(cancelTask = cancellation::request)
        val owned = taskId()
        sessions.register(execution)

        assertTrue(execution.launch { listener ->
            assertEquals(1, sessions.stopOwned())
            assertEquals(emptyList(), cancelled)
            assertEquals(1, sessions.activeCount())
            listener.onStart("", owned)
            assertEquals(listOf(owned), cancelled)
            listener.onEnvironmentPrepared(owned)
            listener.onCancel("", owned)
            listener.onEnd("", owned)
            cancellation.terminate()
        })
        assertEquals(listOf(owned), cancelled)
        assertFalse(execution.awaitResult())
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `cancellation retries scheduling when the bound Gradle task environment becomes ready`() = runBlocking {
        val sessions = AffectedRunSessions()
        val attempts = mutableListOf<ExternalSystemTaskId>()
        val cancellation = RecordingCancellation { id ->
            attempts += id
            attempts.size > 1
        }
        val execution = OwnedExternalTaskExecution(cancelTask = cancellation::request)
        val owned = taskId()
        sessions.register(execution)

        assertTrue(execution.launch { listener ->
            assertEquals(1, sessions.stopOwned())
            listener.onStart("", owned)
            assertEquals(listOf(owned), attempts)
            listener.onEnvironmentPrepared(owned)
            listener.onCancel("", owned)
            listener.onEnd("", owned)
            cancellation.terminate()
        })

        assertEquals(listOf(owned, owned), attempts)
        assertFalse(execution.awaitResult())
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `environment readiness starts a fresh Gradle cancel cycle after attempts are exhausted`() = runBlocking {
        val sessions = AffectedRunSessions()
        val scheduled = ArrayDeque<() -> Unit>()
        var attempts = 0
        var terminated = false
        val execution = OwnedExternalTaskExecution(
            cancelTask = { _, onTerminated, onMonitoringStopped, onCancelAttemptsExhausted ->
                monitorGradleCancellation(
                    cancel = { attempts++; false },
                    terminated = { terminated },
                    onTerminated = onTerminated,
                    onMonitoringStopped = onMonitoringStopped,
                    onCancelAttemptsExhausted = onCancelAttemptsExhausted,
                    schedule = { _, action -> scheduled.add(action); true },
                )
            },
        )
        val owned = taskId()
        sessions.register(execution)

        assertTrue(execution.launch { listener ->
            listener.onStart("", owned)
            assertEquals(1, sessions.stopOwned())
        })
        repeat(64) { scheduled.removeFirst().invoke() }
        assertEquals(64, attempts)
        assertEquals(1, scheduled.size)

        execution.listener.onEnvironmentPrepared(owned)
        assertEquals(2, scheduled.size)
        scheduled.removeFirst().invoke()
        scheduled.removeFirst().invoke()
        assertEquals(65, attempts)

        terminated = true
        scheduled.removeFirst().invoke()
        assertFalse(execution.awaitResult())
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `cancellation after binding stops only the exact owned task`() = runBlocking {
        val sessions = AffectedRunSessions()
        val cancelled = mutableListOf<ExternalSystemTaskId>()
        val cancellation = RecordingCancellation {
            cancelled += it
            true
        }
        val execution = OwnedExternalTaskExecution(cancelTask = cancellation::request)
        val owned = taskId()
        val unrelated = taskId()
        sessions.register(execution)

        assertTrue(execution.launch { listener ->
            listener.onStart("", owned)
            listener.onEnvironmentPrepared(owned)
            assertEquals(1, sessions.stopOwned())
            listener.onCancel("", owned)
            listener.onEnd("", owned)
            cancellation.terminate()
        })
        assertEquals(listOf(owned), cancelled)
        assertFalse(cancelled.contains(unrelated))
        assertFalse(execution.awaitResult())
    }

    @Test
    fun `successful callback waits for owned task termination`() = runBlocking {
        val execution = OwnedExternalTaskExecution(cancelTask = { true })
        val owned = taskId()

        assertTrue(execution.launch { listener ->
            listener.onStart("", owned)
            listener.onSuccess("", owned)
            assertTrue(execution.isActive())
            listener.onEnd("", owned)
        })
        assertTrue(execution.awaitResult())
        assertFalse(execution.isActive())
    }

    @Test
    fun `owned execution converts launcher failure to a completed false result`() = runBlocking {
        val sessions = AffectedRunSessions()
        val execution = OwnedExternalTaskExecution(cancelTask = { true })

        assertFalse(runOwnedExternalTask(sessions, execution) { error("launch failed") })
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `owned preparation can be stopped before the external launcher is reached`() = runBlocking {
        val sessions = AffectedRunSessions()
        val preparing = CompletableDeferred<Unit>()
        val prepared = CompletableDeferred<Unit>()
        var launched = false
        val task = async(Dispatchers.Default) {
            runPreparedOwnedExternalTask(
                sessions = sessions,
                execution = OwnedExternalTaskExecution(cancelTask = { true }),
                prepare = {
                    preparing.complete(Unit)
                    prepared.await()
                },
                launch = {
                    launched = true
                },
            )
        }
        preparing.await()

        assertEquals(1, sessions.stopOwned())
        prepared.complete(Unit)

        assertFalse(task.await())
        assertFalse(launched)
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `launcher failure after binding cancels and waits for the exact task end`() = runBlocking {
        val sessions = AffectedRunSessions()
        val owned = taskId()
        val cancelled = CompletableDeferred<ExternalSystemTaskId>()
        val cancellation = RecordingCancellation {
            cancelled.complete(it)
            true
        }
        val execution = OwnedExternalTaskExecution(cancelTask = cancellation::request)
        val task = async(Dispatchers.Default) {
            runOwnedExternalTask(sessions, execution) { listener ->
                listener.onStart("", owned)
                listener.onEnvironmentPrepared(owned)
                error("launch failed after binding")
            }
        }

        assertEquals(owned, cancelled.await())
        assertFalse(task.isCompleted)
        execution.listener.onCancel("", owned)
        execution.listener.onEnd("", owned)
        cancellation.terminate()

        assertFalse(task.await())
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `synchronous launcher return without lifecycle callbacks fails visibly`() = runBlocking {
        val sessions = AffectedRunSessions()

        assertFalse(runOwnedExternalTask(sessions, OwnedExternalTaskExecution(cancelTask = { true })) {})
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `terminal callback completes a launch without listener lifecycle events`() = runBlocking {
        val sessions = AffectedRunSessions()
        val execution = OwnedExternalTaskExecution(cancelTask = { true })

        assertTrue(runOwnedExternalTask(sessions, execution) { execution.callback.onSuccess() })
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `terminal callback does not finish a bound task before listener end`() = runBlocking {
        val execution = OwnedExternalTaskExecution(cancelTask = { true })
        val owned = taskId()

        assertTrue(execution.launch { listener ->
            listener.onStart("", owned)
            execution.callback.onFailure()
            assertTrue(execution.isActive())
            listener.onEnd("", owned)
        })

        assertFalse(execution.awaitResult())
        assertFalse(execution.isActive())
    }

    @Test
    fun `cancelled bound task waits for exact end after the synchronous launcher returns`() = runBlocking {
        val sessions = AffectedRunSessions()
        val owned = taskId()
        val cancellation = RecordingCancellation { true }
        val execution = OwnedExternalTaskExecution(cancelTask = cancellation::request)
        sessions.register(execution)

        assertTrue(execution.launch { listener ->
            listener.onStart("", owned)
            listener.onEnvironmentPrepared(owned)
            assertEquals(1, sessions.stopOwned())
            execution.callback.onFailure()
            assertTrue(execution.isActive())
        })

        assertNull(withTimeoutOrNull(250) { execution.awaitResult() })
        assertTrue(execution.isActive())
        execution.listener.onEnd("", owned)
        assertNull(withTimeoutOrNull(250) { execution.awaitResult() })
        assertTrue(execution.isActive())
        cancellation.terminate()
        assertFalse(withTimeout(1_000) { execution.awaitResult() })
        assertFalse(execution.isActive())
    }

    @Test
    fun `late exact end restarts terminal monitoring after its scheduler stops`() = runBlocking {
        val sessions = AffectedRunSessions()
        val owned = taskId()
        var requests = 0
        var monitoringStopped: () -> Unit = {}
        var terminated: () -> Unit = {}
        val execution = OwnedExternalTaskExecution(cancelTask = { _, onTerminated, onStopped, _ ->
            requests++
            terminated = onTerminated
            monitoringStopped = onStopped
            true
        })
        sessions.register(execution)

        assertTrue(execution.launch { listener ->
            listener.onStart("", owned)
            assertEquals(1, sessions.stopOwned())
            listener.onEnd("", owned)
        })
        assertEquals(1, requests)
        assertTrue(execution.isActive())

        monitoringStopped()
        assertEquals(2, requests)
        assertTrue(execution.isActive())
        terminated()

        assertFalse(execution.awaitResult())
        assertFalse(execution.isActive())
    }

    @Test
    fun `cancellation before registered launch never calls the launcher`() = runBlocking {
        val sessions = AffectedRunSessions()
        var launched = false

        coroutineScope {
            val preparing = CompletableDeferred<Unit>()
            val prepared = CompletableDeferred<Unit>()
            val task = async(Dispatchers.Default) {
                runPreparedOwnedExternalTask(
                    sessions = sessions,
                    execution = OwnedExternalTaskExecution(cancelTask = { true }),
                    prepare = {
                        preparing.complete(Unit)
                        withContext(NonCancellable) { prepared.await() }
                    },
                    launch = { launched = true },
                )
            }
            preparing.await()
            task.cancel()
            prepared.complete(Unit)
            assertFailsWith<CancellationException> { task.await() }
        }

        assertFalse(launched)
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `coroutine cancellation waits for the bound task to terminate`() = runBlocking {
        val sessions = AffectedRunSessions()
        val owned = taskId()
        val cancelled = CompletableDeferred<ExternalSystemTaskId>()
        val cancellation = RecordingCancellation {
            cancelled.complete(it)
            true
        }
        val execution = OwnedExternalTaskExecution(cancelTask = cancellation::request)
        val launched = CompletableDeferred<Unit>()
        val finishLauncher = CountDownLatch(1)
        val task = async(Dispatchers.Default) {
            runOwnedExternalTask(sessions, execution) { listener ->
                listener.onStart("", owned)
                listener.onEnvironmentPrepared(owned)
                launched.complete(Unit)
                finishLauncher.await()
                listener.onCancel("", owned)
                listener.onEnd("", owned)
                cancellation.terminate()
            }
        }
        launched.await()

        task.cancel()
        assertEquals(owned, cancelled.await())
        while (!task.isCancelled) yield()
        assertFalse(task.isCompleted)

        finishLauncher.countDown()
        assertFailsWith<CancellationException> { task.await() }
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `coroutine cancellation before binding stops the late exact task`() = runBlocking {
        val sessions = AffectedRunSessions()
        val owned = taskId()
        val requested = CompletableDeferred<ExternalSystemTaskNotificationListener>()
        val cancelled = CompletableDeferred<ExternalSystemTaskId>()
        val allowBinding = CountDownLatch(1)
        val allowTermination = CountDownLatch(1)
        val cancellation = RecordingCancellation {
            cancelled.complete(it)
            true
        }
        val execution = OwnedExternalTaskExecution(cancelTask = cancellation::request)
        val task = async(Dispatchers.Default) {
            runOwnedExternalTask(sessions, execution) { listener ->
                requested.complete(listener)
                allowBinding.await()
                listener.onStart("", owned)
                listener.onEnvironmentPrepared(owned)
                allowTermination.await()
                listener.onCancel("", owned)
                listener.onEnd("", owned)
                cancellation.terminate()
            }
        }
        requested.await()

        try {
            task.cancel()
            assertNull(withTimeoutOrNull(250) { task.join() })
            assertEquals(1, sessions.activeCount())

            allowBinding.countDown()
            assertEquals(owned, withTimeout(1_000) { cancelled.await() })
            assertNull(withTimeoutOrNull(250) { task.join() })
        } finally {
            allowBinding.countDown()
            allowTermination.countDown()
        }

        assertFailsWith<CancellationException> { task.await() }
        assertEquals(0, sessions.activeCount())
    }

    private fun taskId(): ExternalSystemTaskId = ExternalSystemTaskId.create(
        ProjectSystemId("GRADLE"),
        ExternalSystemTaskType.EXECUTE_TASK,
        "affected-test",
    )

    private class RecordingCancellation(
        private val cancel: (ExternalSystemTaskId) -> Boolean,
    ) {
        private var termination: (() -> Unit)? = null

        @Suppress("UNUSED_PARAMETER")
        fun request(
            id: ExternalSystemTaskId,
            onTerminated: () -> Unit,
            onMonitoringStopped: () -> Unit,
            onCancelAttemptsExhausted: () -> Unit,
        ): Boolean {
            val accepted = cancel(id)
            if (accepted) {
                termination = onTerminated
            }
            return accepted
        }

        fun terminate() {
            checkNotNull(termination).invoke()
        }
    }

    private class RecordingSession(private val active: Boolean) : AffectedOwnedSession {
        var stopped = false

        override fun isActive(): Boolean = active && !stopped

        override fun stopIfActive(): Boolean {
            if (!isActive()) return false
            stopped = true
            return true
        }
    }
}
