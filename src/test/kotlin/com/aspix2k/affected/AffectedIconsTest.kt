package com.aspix2k.affected

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class AffectedIconsTest {

    @Test
    fun `нулевой и отрицательный счётчик дают спокойную сетку`() {
        assertSame(AffectedIcons.Action, AffectedIcons.withCount(0))
        assertSame(AffectedIcons.Action, AffectedIcons.withCount(-5))
    }

    @Test
    fun `сетка заполняется по мере роста числа модулей`() {
        val few = AffectedIcons.withCount(1)
        val some = AffectedIcons.withCount(4)
        val many = AffectedIcons.withCount(10)
        val all = AffectedIcons.withCount(100)

        assertNotEquals(few, some)
        assertNotEquals(some, many)
        assertNotEquals(many, all)
    }

    @Test
    fun `соседние значения одного диапазона дают одну иконку`() {
        assertSame(AffectedIcons.withCount(3), AffectedIcons.withCount(6))
        assertSame(AffectedIcons.withCount(20), AffectedIcons.withCount(500))
    }

    @Test
    fun `анимация запуска имеет размер иконки тулбара`() {
        assertEquals(20, AffectedIcons.Running.iconWidth)
        assertEquals(20, AffectedIcons.Running.iconHeight)
    }
}
