package com.aspix2k.affected

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class AffectedIconsTest {

    @Test
    fun `zero and negative counts use the idle grid`() {
        assertSame(AffectedIcons.Action, AffectedIcons.withCount(0))
        assertSame(AffectedIcons.Action, AffectedIcons.withCount(-5))
    }

    @Test
    fun `the grid fills as the module count grows`() {
        val few = AffectedIcons.withCount(1)
        val some = AffectedIcons.withCount(4)
        val many = AffectedIcons.withCount(10)
        val all = AffectedIcons.withCount(100)

        assertNotEquals(few, some)
        assertNotEquals(some, many)
        assertNotEquals(many, all)
    }

    @Test
    fun `adjacent values in one range use the same icon`() {
        assertSame(AffectedIcons.withCount(3), AffectedIcons.withCount(6))
        assertSame(AffectedIcons.withCount(20), AffectedIcons.withCount(500))
    }

    @Test
    fun `animation respects its setting`() {
        assertSame(AffectedIcons.Running, AffectedIcons.forState(VerificationStatus.RUNNING, 4, true))
        assertSame(AffectedIcons.withCount(4), AffectedIcons.forState(VerificationStatus.RUNNING, 4, false))
    }

    @Test
    fun `idle state shows the current module count`() {
        assertSame(AffectedIcons.withCount(4), AffectedIcons.forState(VerificationStatus.IDLE, 4, true))
    }
}
