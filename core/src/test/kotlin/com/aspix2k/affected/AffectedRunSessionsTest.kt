package com.aspix2k.affected

import kotlin.test.Test
import kotlin.test.assertEquals

class AffectedRunSessionsTest {

    @Test
    fun `stop terminates only registered active sessions`() {
        val sessions = AffectedRunSessions()
        val owned = RecordingSession(active = true)
        val finished = RecordingSession(active = false)
        val foreign = RecordingSession(active = true)

        sessions.register(owned)
        sessions.register(finished)

        assertEquals(1, sessions.activeCount())
        assertEquals(1, sessions.stopOwned())
        assertEquals(true, owned.stopped)
        assertEquals(false, finished.stopped)
        assertEquals(false, foreign.stopped)
        assertEquals(0, sessions.activeCount())
    }

    @Test
    fun `external execute tasks are claimed only while a run expects them`() {
        val sessions = AffectedRunSessions()

        assertEquals(false, sessions.claimExternal())
        sessions.expectExternal()
        assertEquals(true, sessions.claimExternal())
        assertEquals(false, sessions.claimExternal())
    }

    private class RecordingSession(private val active: Boolean) : AffectedOwnedSession {
        var stopped = false

        override fun isActive(): Boolean = active && !stopped

        override fun stopIfActive(): Boolean {
            if (!isActive()) return false
            stopped = true
            return true
        }
    }
}
