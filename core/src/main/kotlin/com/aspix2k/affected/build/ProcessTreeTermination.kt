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
    private val initialPass = CountDownLatch(1)
    private val lock = Any()
    private val scanLock = Any()
    private val known = linkedSetOf(root)
    private val tracking = AtomicReference<ScheduledFuture<*>?>()

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
    internal val isClosed: Boolean get() = tracking.get() == null

    fun request() {
        if (!requested.compareAndSet(false, true)) return
        deadlineNanos = System.nanoTime() + timeoutNanos
        stopTracking()
        runCatching { executor.execute(::initialTermination) }.onFailure {
            scanFailed = true
            destroyKnown()
            initialPass.countDown()
        }
    }

    fun await(): Boolean {
        request()
        var restoreInterrupt = Thread.interrupted()
        return try {
            if (!awaitInitialPass()) return false
            while (remainingNanos() > 0) {
                val added = scan(deadlineNanos)
                destroyKnown()
                if (!scanFailed && !added && allTerminated()) {
                    return true
                }
                Thread.sleep(TERMINATION_POLL_MILLIS)
            }
            false
        } catch (_: InterruptedException) {
            restoreInterrupt = true
            false
        } finally {
            stopTracking()
            if (restoreInterrupt) Thread.currentThread().interrupt()
        }
    }

    fun close() {
        stopTracking()
    }

    private fun initialTermination() {
        try {
            scan(deadlineNanos)
            runCatching(afterInitialScan).onFailure { error ->
                scanFailed = true
                if (error is InterruptedException) Thread.currentThread().interrupt()
            }
            scan(deadlineNanos)
            destroyKnown()
            runCatching(afterInitialPass).onFailure { error ->
                scanFailed = true
                if (error is InterruptedException) Thread.currentThread().interrupt()
            }
        } finally {
            initialPass.countDown()
        }
    }

    private fun awaitInitialPass(): Boolean {
        val remaining = remainingNanos()
        return remaining > 0 && initialPass.await(remaining, TimeUnit.NANOSECONDS)
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
                            if (child in known) {
                                false
                            } else if (known.size >= maxProcesses) {
                                scanFailed = true
                                false
                            } else {
                                known.add(child)
                            }
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

    private fun destroyKnown() {
        synchronized(lock) { known.toList().asReversed() }.forEach { process ->
            if (isAlive(process)) runCatching { process.destroyForcibly() }
        }
    }

    private fun allTerminated(): Boolean = synchronized(lock) { known.none(::isAlive) }

    private fun isAlive(process: ProcessHandle): Boolean = runCatching { process.isAlive }.getOrElse {
        scanFailed = true
        true
    }

    private fun remainingNanos(): Long = deadlineNanos - System.nanoTime()

    private fun stopTracking() {
        tracking.getAndSet(null)?.cancel(false)
    }
}

private const val TERMINATION_POLL_MILLIS = 25L
private const val TRACKING_POLL_MILLIS = 25L
private const val TERMINATION_TIMEOUT_NANOS = 5_000_000_000L
private const val TRACKING_SCAN_TIMEOUT_NANOS = 100_000_000L
private const val MAX_TRACKED_PROCESSES = 4_096
