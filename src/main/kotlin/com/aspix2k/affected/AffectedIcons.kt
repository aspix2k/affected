package com.aspix2k.affected

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import javax.swing.Icon

object AffectedIcons {

    val Action: Icon = load("affected")

    val Module: Icon = load("module")

    val Check: Icon = load("check")

    private val Few: Icon = load("affected_few")

    private val Some: Icon = load("affected_some")

    private val Many: Icon = load("affected_many")

    private val All: Icon = load("affected_all")

    private val runningFrames: Array<Icon> by lazy {
        Array(FRAMES) { load("affected_run${it + 1}") }
    }

    @Suppress("SpreadOperator")
    val Running: Icon by lazy {
        AnimatedIcon(FRAME_DELAY_MS, *runningFrames)
    }

    fun withCount(count: Int): Icon = when {
        count <= 0 -> Action
        count <= 2 -> Few
        count <= 6 -> Some
        count <= 15 -> Many
        else -> All
    }

    private fun load(name: String): Icon =
        IconLoader.getIcon("/icons/$name.svg", AffectedIcons::class.java)

    private const val FRAMES = 12
    private const val FRAME_DELAY_MS = 80
}
