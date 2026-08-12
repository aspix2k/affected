package com.aspix2k.affected

import kotlin.test.Test
import kotlin.test.assertEquals

class DumbModeRefreshTest {

    @Test
    fun `entering and leaving dumb mode both invalidate the analysis`() {
        var invalidations = 0
        val listener = AffectedDumbModeListener { invalidations++ }

        listener.enteredDumbMode()
        listener.exitDumbMode()

        assertEquals(2, invalidations)
    }
}
