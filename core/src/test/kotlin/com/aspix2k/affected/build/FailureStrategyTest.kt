package com.aspix2k.affected.build

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailureStrategyTest {

    @Test
    fun `the full plan continues after a failure`() {
        assertTrue(continuesAfterFailure(stopAfterFirstFailure = false))
    }

    @Test
    fun `stop after the first failure does not continue`() {
        assertFalse(continuesAfterFailure(stopAfterFirstFailure = true))
    }
}
