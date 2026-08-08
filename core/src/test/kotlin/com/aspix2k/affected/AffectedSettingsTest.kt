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
    fun `commit and push guards are disabled by default`() {
        val settings = AffectedSettings.State()

        assertFalse(settings.runBeforeCommit)
        assertFalse(settings.runBeforePush)
    }

    @Test
    fun `animation is enabled by default`() {
        assertTrue(AffectedSettings.State().animateWhileRunning)
    }
}
