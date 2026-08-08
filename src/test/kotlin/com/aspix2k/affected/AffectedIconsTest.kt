package com.aspix2k.affected

import kotlin.test.Test
import kotlin.test.assertEquals

class AffectedIconsTest {

    @Test
    fun `отрицательный и нулевой счётчик дают базовую иконку`() {
        assertEquals(AffectedIcons.Action, AffectedIcons.withCount(0))
        assertEquals(AffectedIcons.Action, AffectedIcons.withCount(-5))
    }

    @Test
    fun `большие значения счётчика схлопываются в один вариант`() {
        val huge = AffectedIcons.withCount(Int.MAX_VALUE)
        val slightly = AffectedIcons.withCount(100)
        assertEquals(huge, slightly, "всё, что больше порога, использует одну иконку")
    }

    @Test
    fun `счётчик кэшируется а не создаётся заново`() {
        assertEquals(AffectedIcons.withCount(7), AffectedIcons.withCount(7))
    }
}
