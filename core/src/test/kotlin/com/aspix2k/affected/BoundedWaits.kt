package com.aspix2k.affected

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val blockingTestBudget = 2.minutes
private val latchBudget = 30.seconds

private class Completed<T>(val value: T)

fun <T> runBoundedBlocking(
    budget: Duration = blockingTestBudget,
    block: suspend CoroutineScope.() -> T,
): T = runBlocking {
    val completed = withTimeoutOrNull(budget) { Completed(block()) }
    checkNotNull(completed) { "The test body did not finish within $budget" }.value
}

fun CountDownLatch.awaitBounded() {
    check(await(latchBudget.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
        "A test latch with count $count was not released within $latchBudget"
    }
}
