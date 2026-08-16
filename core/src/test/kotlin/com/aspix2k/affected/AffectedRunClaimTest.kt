package com.aspix2k.affected

import com.intellij.execution.process.ProcessHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AffectedRunClaimTest {

    @Test
    fun `claim publication is atomic with stop`() = runBlocking {
        val sessions = AffectedRunSessions()
        val creating = CountDownLatch(1)
        val allowPublication = CountDownLatch(1)
        val claimed = async(Dispatchers.Default) {
            sessions.claim {
                creating.countDown()
                allowPublication.await()
                claim()
            }
        }
        creating.await()
        val stopped = async(Dispatchers.Default) { sessions.stopOwned() }

        assertNull(withTimeoutOrNull(100) { stopped.await() })
        allowPublication.countDown()

        val claim = requireNotNull(claimed.await())
        assertEquals(1, stopped.await())
        assertFalse(claim.markRunning())
        claim.close()
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `stop cannot overtake an in-flight run transition`() = runBlocking {
        val sessions = AffectedRunSessions()
        val marking = CountDownLatch(1)
        val allowRunning = CountDownLatch(1)
        val claim = requireNotNull(sessions.claim {
            claim(markRunning = {
                marking.countDown()
                allowRunning.await()
                true
            })
        })
        val marked = async(Dispatchers.Default) { claim.markRunning() }
        marking.await()
        val stopped = async(Dispatchers.Default) { sessions.stopOwned() }

        assertNull(withTimeoutOrNull(100) { stopped.await() })
        allowRunning.countDown()

        assertTrue(marked.await())
        assertEquals(1, stopped.await())
        assertTrue(claim.isCancellationRequested())
        claim.close()
    }

    @Test
    fun `successful completion unregisters the run before publishing its result`() = runBlocking {
        val sessions = AffectedRunSessions()
        val releasing = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val claim = requireNotNull(sessions.claim {
            claim(release = {
                releasing.countDown()
                allowRelease.await()
            })
        })
        val completed = async(Dispatchers.Default) { claim.complete(passed = true) }
        releasing.await()

        assertEquals(0, sessions.stopOwned())
        allowRelease.countDown()

        assertTrue(completed.await())
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `project disposal rejects later claims and sessions`() {
        val sessions = AffectedRunSessions()
        val claim = requireNotNull(sessions.claim(::claim))
        val running = RecordingSession(active = true)
        sessions.register(running)

        sessions.dispose()
        val late = RecordingSession(active = true)
        sessions.register(late)

        assertTrue(claim.isCancellationRequested())
        assertTrue(running.stopped)
        assertTrue(late.stopped)
        assertNull(sessions.claim(::claim))
        claim.close()
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `cancellation before process binding stops the late exact handler`() {
        val sessions = AffectedRunSessions()
        var collectorCancelled = false
        val execution = OwnedProcessExecution { collectorCancelled = true }
        sessions.register(execution)

        assertEquals(1, sessions.stopOwned())
        val handler = RecordingProcessHandler()
        handler.startNotify()
        execution.bind(handler)

        assertTrue(collectorCancelled)
        assertTrue(handler.destroyed)
        execution.finish()
        sessions.unregister(execution)
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `terminating process remains owned until its terminal event`() {
        val sessions = AffectedRunSessions()
        val handler = RecordingProcessHandler()
        handler.startNotify()

        assertTrue(sessions.register(handler))
        handler.destroyProcess()

        assertTrue(handler.isProcessTerminating)
        assertEquals(1, sessions.activeCount())
        handler.finish()
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `stop cannot overtake owned process completion`() = runBlocking {
        val sessions = AffectedRunSessions()
        val completing = CountDownLatch(1)
        val allowCompletion = CountDownLatch(1)
        val execution = OwnedProcessExecution()
        sessions.register(execution)
        val completed = async(Dispatchers.Default) {
            execution.finish { accepted ->
                assertTrue(accepted)
                completing.countDown()
                allowCompletion.await()
            }
        }
        completing.await()
        val stopped = async(Dispatchers.Default) { sessions.stopOwned() }

        assertNull(withTimeoutOrNull(100) { stopped.await() })
        allowCompletion.countDown()

        assertTrue(completed.await())
        assertEquals(0, stopped.await())
        sessions.unregister(execution)
    }

    @Test
    fun `cancelled process ownership remains pending until cleanup finishes`() = runBlocking {
        val sessions = AffectedRunSessions()
        val execution = OwnedProcessExecution()
        sessions.register(execution)

        assertEquals(1, sessions.stopOwned())
        val finished = async(Dispatchers.Default) { execution.awaitFinished() }
        assertNull(withTimeoutOrNull(100) { finished.await() })

        assertFalse(execution.finish())
        assertFalse(finished.await())
        sessions.unregister(execution)
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `stop before claimed group dispatch prevents adapter invocation`() = runBlocking {
        val sessions = AffectedRunSessions()
        val claim = requireNotNull(sessions.claim(::claim))
        var invoked = false

        assertTrue(claim.markRunning())
        assertEquals(1, sessions.stopOwned())
        val passed = runClaimedGroups(
            claim,
            listOf(TaskGroup("GRADLE", "/repo", listOf(":test"))),
            Dispatchers.Default,
            stopAfterFirstFailure = false,
        ) {
            invoked = true
            true
        }

        assertFalse(passed)
        assertFalse(invoked)
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `stop terminates a running group and prevents its pending sibling`() = runBlocking {
        val sessions = AffectedRunSessions()
        val claim = requireNotNull(sessions.claim(::claim))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val running = RecordingSession(active = true)
        val invoked = mutableListOf<String>()
        val dispatcher = QueueingDispatcher()
        val groups = listOf(
            TaskGroup("GRADLE", "/repo/first", listOf(":first:test")),
            TaskGroup("MAVEN", "/repo/second", listOf(":second:test")),
        )

        assertTrue(claim.markRunning())
        val result = async(Dispatchers.Default) {
            runClaimedGroups(claim, groups, dispatcher, stopAfterFirstFailure = false) { group ->
                invoked += group.root
                if (group == groups.first()) {
                    sessions.register(running)
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    !running.stopped
                } else {
                    true
                }
            }
        }
        while (dispatcher.size < groups.size) yield()
        assertTrue(dispatcher.runNext())
        entered.await()

        assertEquals(1, sessions.stopOwned())
        assertTrue(running.stopped)
        release.complete(Unit)
        while (!result.isCompleted) {
            if (!dispatcher.runNext()) yield()
        }

        assertFalse(result.await())
        assertEquals(listOf("/repo/first"), invoked)
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `first failed group prevents pending sibling dispatch`() = runBlocking {
        val sessions = AffectedRunSessions()
        val claim = requireNotNull(sessions.claim(::claim))
        val invoked = mutableListOf<String>()
        val dispatcher = QueueingDispatcher()
        val groups = listOf(
            TaskGroup("GRADLE", "/repo/first", listOf(":first:test")),
            TaskGroup("MAVEN", "/repo/second", listOf(":second:test")),
        )

        assertTrue(claim.markRunning())
        val result = async(Dispatchers.Default) {
            runClaimedGroups(claim, groups, dispatcher, stopAfterFirstFailure = true) { group ->
                invoked += group.root
                group != groups.first()
            }
        }
        while (dispatcher.size < groups.size) yield()
        assertTrue(dispatcher.runNext())
        assertTrue(dispatcher.runNext())

        assertFalse(result.await())
        assertEquals(listOf("/repo/first"), invoked)
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `first failed group stops running sibling and waits for its cleanup`() = runBlocking {
        val sessions = AffectedRunSessions()
        val claim = requireNotNull(sessions.claim(::claim))
        val siblingStarted = CompletableDeferred<Unit>()
        val allowSiblingCleanup = CompletableDeferred<Unit>()
        val sibling = WaitingSession()
        val unrelated = RecordingSession(active = true)
        val groups = listOf(
            TaskGroup("GRADLE", "/repo/first", listOf(":first:test")),
            TaskGroup("MAVEN", "/repo/second", listOf(":second:test")),
        )

        assertTrue(sessions.register(unrelated))
        assertTrue(claim.markRunning())
        val result = async(Dispatchers.Default) {
            runClaimedGroups(claim, groups, Dispatchers.Default, stopAfterFirstFailure = true) { group ->
                if (group == groups.first()) {
                    siblingStarted.await()
                    false
                } else {
                    assertTrue(withContext(Dispatchers.IO) { sessions.register(sibling) })
                    siblingStarted.complete(Unit)
                    withContext(NonCancellable) {
                        sibling.stopped.await()
                        allowSiblingCleanup.await()
                        sibling.finish()
                        sessions.unregister(sibling)
                    }
                    true
                }
            }
        }

        sibling.stopped.await()
        assertFalse(result.isCompleted)
        assertFalse(unrelated.stopped)
        allowSiblingCleanup.complete(Unit)

        assertFalse(result.await())
        assertFalse(unrelated.stopped)
        sessions.unregister(unrelated)
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `disabled fail fast lets every group finish`() = runBlocking {
        val sessions = AffectedRunSessions()
        val claim = requireNotNull(sessions.claim(::claim))
        val invoked = mutableListOf<String>()
        val dispatcher = QueueingDispatcher()
        val groups = listOf(
            TaskGroup("GRADLE", "/repo/first", listOf(":first:test")),
            TaskGroup("MAVEN", "/repo/second", listOf(":second:test")),
        )

        assertTrue(claim.markRunning())
        val result = async(Dispatchers.Default) {
            runClaimedGroups(claim, groups, dispatcher, stopAfterFirstFailure = false) { group ->
                invoked += group.root
                group != groups.first()
            }
        }
        while (dispatcher.size < groups.size) yield()
        assertTrue(dispatcher.runNext())
        assertTrue(dispatcher.runNext())

        assertFalse(result.await())
        assertEquals(groups.map(TaskGroup::root), invoked)
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `failed run does not cancel the next claim`() = runBlocking {
        val sessions = AffectedRunSessions()
        val first = requireNotNull(sessions.claim(::claim))

        assertTrue(first.markRunning())
        assertFalse(
            runClaimedGroups(
                first,
                listOf(TaskGroup("GRADLE", "/repo/first", listOf(":first:test"))),
                Dispatchers.Default,
                stopAfterFirstFailure = true,
            ) { false }
        )

        val second = requireNotNull(sessions.claim(::claim))
        assertTrue(second.markRunning())
        assertTrue(
            runClaimedGroups(
                second,
                listOf(TaskGroup("MAVEN", "/repo/second", listOf(":second:test"))),
                Dispatchers.Default,
                stopAfterFirstFailure = true,
            ) { true }
        )
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `failed run does not reject an unrelated late session`() = runBlocking {
        val sessions = AffectedRunSessions()
        val claim = requireNotNull(sessions.claim(::claim))
        val siblingStarted = CompletableDeferred<Unit>()
        val allowSiblingCleanup = CompletableDeferred<Unit>()
        val sibling = WaitingSession()
        val groups = listOf(
            TaskGroup("GRADLE", "/repo/first", listOf(":first:test")),
            TaskGroup("MAVEN", "/repo/second", listOf(":second:test")),
        )

        assertTrue(claim.markRunning())
        val result = async(Dispatchers.Default) {
            runClaimedGroups(claim, groups, Dispatchers.Default, stopAfterFirstFailure = true) { group ->
                if (group == groups.first()) {
                    siblingStarted.await()
                    false
                } else {
                    assertTrue(sessions.register(sibling))
                    siblingStarted.complete(Unit)
                    withContext(NonCancellable) {
                        sibling.stopped.await()
                        allowSiblingCleanup.await()
                        sibling.finish()
                        sessions.unregister(sibling)
                    }
                    true
                }
            }
        }
        sibling.stopped.await()
        val unrelated = RecordingSession(active = true)

        assertTrue(sessions.register(unrelated))
        assertFalse(unrelated.stopped)
        allowSiblingCleanup.complete(Unit)

        assertFalse(result.await())
        assertFalse(unrelated.stopped)
        sessions.unregister(unrelated)
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `a stopped claim rejects late sessions without affecting the next run`() {
        val sessions = AffectedRunSessions()
        val stopped = requireNotNull(sessions.claim(::claim))

        assertEquals(1, sessions.stopOwned())
        val late = RecordingSession(active = true)
        sessions.register(late)
        assertTrue(late.stopped)
        stopped.close()

        val current = requireNotNull(sessions.claim(::claim))
        val currentSession = RecordingSession(active = true)
        sessions.register(currentSession)

        assertFalse(currentSession.stopped)
        assertEquals(1, sessions.activeCount())
        current.close()
    }

    private fun claim(
        markRunning: () -> Boolean = { true },
        release: () -> Unit = {},
    ) = AffectedRunClaim(
        snapshot = AffectedStateSnapshot(
            revision = 1,
            analysisStatus = AnalysisStatus.READY,
            modules = emptyList(),
            verificationStatus = VerificationStatus.PREPARING,
        ),
        changes = null,
        prepared = null,
        markRunning = markRunning,
        release = release,
    )

    private class RecordingProcessHandler : ProcessHandler() {
        var destroyed = false

        fun finish() = notifyProcessTerminated(1)

        override fun destroyProcessImpl() {
            destroyed = true
        }

        override fun detachProcessImpl() = Unit

        override fun detachIsDefault(): Boolean = false

        override fun getProcessInput(): OutputStream? = null
    }

    private class QueueingDispatcher : CoroutineDispatcher() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()

        val size: Int get() = tasks.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.add(block)
        }

        fun runNext(): Boolean = tasks.poll()?.let { task ->
            task.run()
            true
        } ?: false
    }

    private class RecordingSession(private val active: Boolean) : AffectedOwnedSession {
        @Volatile
        var stopped = false

        override fun isActive(): Boolean = active && !stopped

        override fun stopIfActive(): Boolean {
            if (!isActive()) return false
            stopped = true
            return true
        }
    }

    private class WaitingSession : AffectedOwnedSession {
        private val lock = Any()
        val stopped = CompletableDeferred<Unit>()
        private var stopping = false
        private var finished = false

        fun finish() = synchronized(lock) {
            finished = true
        }

        override fun isActive(): Boolean = synchronized(lock) { !finished }

        override fun stopIfActive(): Boolean {
            val accepted = synchronized(lock) {
                if (finished || stopping) return false
                stopping = true
                true
            }
            if (accepted) stopped.complete(Unit)
            return accepted
        }
    }
}
