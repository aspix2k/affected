package com.aspix2k.affected

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private val latchBudget = 30.seconds

fun CountDownLatch.awaitBounded() {
    check(await(latchBudget.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
        "A test latch with count $count was not released within $latchBudget"
    }
}
