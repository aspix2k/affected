package com.aspix2k.affected.build

import com.intellij.util.concurrency.AppExecutorUtil
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class ProcessTreeTermination(
    private val root: ProcessHandle,
    private val childSnapshot: ((ProcessHandle) -> Iterable<ProcessHandle>)? = null,
    private val afterInitialScan: () -> Unit = {},
    private val afterInitialPass: () -> Unit = {},
    private val afterTrackingScan: (Set<ProcessHandle>) -> Unit = {},
    private val timeoutNanos: Long = TERMINATION_TIMEOUT_NANOS,
    private val maxProcesses: Int = MAX_TRACKED_PROCESSES,
    private val executor: ScheduledExecutorService = AppExecutorUtil.getAppScheduledExecutorService(),
) {

    private val requested = AtomicBoolean()
    private val completed = AtomicBoolean()
    private val initialPassStarted = AtomicBoolean()
    private val completion = CountDownLatch(1)
    private val lock = Any()
    private val scanLock = Any()
    private val known = linkedSetOf(root)
    private val childrenByParent = linkedMapOf<ProcessHandle, MutableSet<ProcessHandle>>()
    private val tracking = AtomicReference<ScheduledFuture<*>?>()
    private val termination = AtomicReference<ScheduledFuture<*>?>()

    @Volatile
    private var terminationProven = false

    @Volatile
    private var deadlineNanos = Long.MAX_VALUE

    @Volatile
    private var scanFailed = false

    init {
        tracking.set(
            executor.scheduleWithFixedDelay(
                ::track,
                TRACKING_POLL_MILLIS,
                TRACKING_POLL_MILLIS,
                TimeUnit.MILLISECONDS,
            ),
        )
    }

    val isRequested: Boolean get() = requested.get()
    internal val isClosed: Boolean get() = tracking.get() == null && termination.get() == null

    fun request() {
        if (!requested.compareAndSet(false, true)) return
        deadlineNanos = System.nanoTime() + timeoutNanos
        stopTracking()
        runCatching {
            executor.scheduleWithFixedDelay(
                ::terminationPass,
                0,
                TERMINATION_POLL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }.onSuccess { scheduled ->
            termination.set(scheduled)
            if (completed.get()) stopTermination()
        }.onFailure {
            scanFailed = true
            destroyLeaves()
            complete(false)
        }
    }

    fun await(): Boolean {
        request()
        var restoreInterrupt = Thread.interrupted()
        return try {
            val remaining = remainingNanos()
            val finished = completion.count == 0L ||
                (remaining > 0 && completion.await(remaining, TimeUnit.NANOSECONDS))
            finished && terminationProven
        } catch (_: InterruptedException) {
            restoreInterrupt = true
            false
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt()
        }
    }

    fun close() {
        stopTracking()
    }

    private fun terminationPass() {
        if (completed.get()) return
        if (remainingNanos() <= 0) return complete(false)
        val added = if (initialPassStarted.compareAndSet(false, true)) {
            scan(deadlineNanos)
            runCatching(afterInitialScan).onFailure { error ->
                scanFailed = true
                if (error is InterruptedException) Thread.currentThread().interrupt()
            }
            val discovered = scan(deadlineNanos)
            destroyLeaves()
            discovered
        } else {
            scan(deadlineNanos).also { destroyLeaves() }
        }
        if (!scanFailed && !added && allTerminated()) return complete(true)
        if (remainingNanos() <= 0) complete(false)
    }

    private fun track() {
        if (requested.get()) return stopTracking()
        val passDeadline = System.nanoTime() + TRACKING_SCAN_TIMEOUT_NANOS
        scan(passDeadline)
        runCatching { afterTrackingScan(synchronized(lock) { known.toSet() }) }.onFailure {
            scanFailed = true
        }
    }

    private fun scan(scanDeadline: Long): Boolean = synchronized(scanLock) {
        if (System.nanoTime() >= scanDeadline) {
            scanFailed = true
            return@synchronized false
        }
        var added = false
        val queue = ArrayDeque(synchronized(lock) { known.toList() })
        val visited = hashSetOf<ProcessHandle>()
        var complete = true
        while (queue.isNotEmpty() && complete) {
            if (System.nanoTime() >= scanDeadline) {
                scanFailed = true
                complete = false
            } else {
                val process = queue.removeFirst()
                if (visited.add(process)) {
                    complete = visitChildren(process, scanDeadline) { child ->
                        val wasAdded = synchronized(lock) {
                            val added = when {
                                child in known -> false
                                known.size >= maxProcesses -> {
                                    scanFailed = true
                                    false
                                }
                                else -> known.add(child)
                            }
                            if (child in known) childrenByParent.getOrPut(process, ::linkedSetOf).add(child)
                            added
                        }
                        if (wasAdded) added = true
                        if (!visited.contains(child)) queue.addLast(child)
                    }
                }
            }
        }
        if (!complete) scanFailed = true
        added
    }

    private fun visitChildren(
        process: ProcessHandle,
        scanDeadline: Long,
        visit: (ProcessHandle) -> Unit,
    ): Boolean = runCatching {
        val injected = childSnapshot
        if (injected != null) {
            val iterator = injected(process).iterator()
            while (iterator.hasNext()) {
                if (System.nanoTime() >= scanDeadline || trackedCount() >= maxProcesses) {
                    return@runCatching false
                }
                visit(iterator.next())
            }
        } else {
            process.children().use { children ->
                val iterator = children.iterator()
                while (iterator.hasNext()) {
                    if (System.nanoTime() >= scanDeadline || trackedCount() >= maxProcesses) return@use false
                    visit(iterator.next())
                }
            }
        }
        true
    }.getOrElse { false }

    private fun trackedCount(): Int = synchronized(lock) { known.size }

    private fun destroyLeaves() {
        val leaves = synchronized(lock) {
            val alive = known.filter(::isAlive)
            val aliveSet = alive.toSet()
            val parents = childrenByParent
                .filter { (parent, children) -> parent in aliveSet && children.any(aliveSet::contains) }
                .keys
            alive.filterNot(parents::contains).ifEmpty { alive }
        }
        leaves.forEach { process ->
            if (isAlive(process)) runCatching { process.destroyForcibly() }
        }
    }

    private fun allTerminated(): Boolean = synchronized(lock) { known.none(::isAlive) }

    private fun isAlive(process: ProcessHandle): Boolean = runCatching { process.isAlive }.getOrElse {
        scanFailed = true
        true
    }

    private fun remainingNanos(): Long = deadlineNanos - System.nanoTime()

    private fun complete(proven: Boolean) {
        if (!completed.compareAndSet(false, true)) return
        runCatching(afterInitialPass).onFailure { error ->
            scanFailed = true
            if (error is InterruptedException) Thread.currentThread().interrupt()
        }
        terminationProven = proven && !scanFailed
        stopTermination()
        completion.countDown()
    }

    private fun stopTracking() {
        tracking.getAndSet(null)?.cancel(false)
    }

    private fun stopTermination() {
        termination.getAndSet(null)?.cancel(false)
    }
}

private const val TERMINATION_POLL_MILLIS = 25L
private const val TRACKING_POLL_MILLIS = 25L
private const val TERMINATION_TIMEOUT_NANOS = 5_000_000_000L
private const val TRACKING_SCAN_TIMEOUT_NANOS = 100_000_000L
private const val MAX_TRACKED_PROCESSES = 4_096
