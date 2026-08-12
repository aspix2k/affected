package com.aspix2k.affected

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import javax.swing.Icon

object AffectedIcons {

    val Action: Icon = load("affected")

    val Module: Icon = load("module")

    val Check: Icon = load("check")

    private val counts: List<Pair<IntRange, Icon>> = listOf(
        1..2 to load("affected_few"),
        3..6 to load("affected_some"),
        7..15 to load("affected_many"),
        16..Int.MAX_VALUE to load("affected_all"),
    )

    private val runningFrames: Array<Icon> by lazy {
        Array(FRAMES) { load("affected_run${it + 1}") }
    }

    @Suppress("SpreadOperator")
    val Running: Icon by lazy {
        AnimatedIcon(FRAME_DELAY_MS, *runningFrames)
    }

    @Suppress("SpreadOperator")
    val DisabledRunning: Icon by lazy {
        AnimatedIcon(FRAME_DELAY_MS, *runningFrames.map(IconLoader::getDisabledIcon).toTypedArray())
    }

    fun withCount(count: Int): Icon =
        counts.firstOrNull { count in it.first }?.second ?: Action

    fun forState(status: VerificationStatus, count: Int, animate: Boolean): Icon = when (status) {
        VerificationStatus.PREPARING,
        VerificationStatus.RUNNING -> if (animate) Running else withCount(count)
        VerificationStatus.IDLE -> withCount(count)
    }

    private fun load(name: String): Icon =
        IconLoader.getIcon("/icons/$name.svg", AffectedIcons::class.java)

    private const val FRAMES = 12
    private const val FRAME_DELAY_MS = 80
}
