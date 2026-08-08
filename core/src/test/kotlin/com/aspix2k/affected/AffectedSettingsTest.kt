package com.aspix2k.affected

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AffectedSettingsTest {

    @Test
    fun `consumer checks are disabled by default`() {
        assertFalse(AffectedSettings.State().checkConsumers)
    }

    @Test
    fun `animation is enabled by default`() {
        assertTrue(AffectedSettings.State().animateWhileRunning)
    }
}
