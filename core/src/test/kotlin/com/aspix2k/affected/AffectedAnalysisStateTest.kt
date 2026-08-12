package com.aspix2k.affected

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AffectedAnalysisStateTest {

    @Test
    fun `invalidation hides a completed result until the latest analysis finishes`() {
        val state = AffectedStateStore()
        val first = state.invalidate()

        assertTrue(state.complete(first, listOf(module(":first"))))
        assertEquals(AnalysisStatus.READY, state.snapshot().analysisStatus)

        val second = state.invalidate()

        assertEquals(AnalysisStatus.ANALYZING, state.snapshot().analysisStatus)
        assertFalse(state.complete(first, listOf(module(":stale"))))
        assertEquals(AnalysisStatus.ANALYZING, state.snapshot().analysisStatus)
        assertTrue(state.complete(second, listOf(module(":second"))))
        assertEquals(listOf(":second"), state.snapshot().modules.map(AffectedModule::id))
    }

    @Test
    fun `only the latest failed analysis becomes unavailable`() {
        val state = AffectedStateStore()
        val stale = state.invalidate()
        val current = state.invalidate()

        assertFalse(state.fail(stale))
        assertEquals(AnalysisStatus.ANALYZING, state.snapshot().analysisStatus)
        assertTrue(state.fail(current))
        assertEquals(AnalysisStatus.UNAVAILABLE, state.snapshot().analysisStatus)
        assertEquals(emptyList(), state.snapshot().modules)
    }

    @Test
    fun `a stale ready snapshot cannot claim a run after invalidation`() {
        val state = AffectedStateStore()
        val revision = state.invalidate()
        assertTrue(state.complete(revision, listOf(module(":ready"))))
        val ready = state.snapshot()

        state.invalidate()

        assertFalse(state.tryMarkRunning(ready.revision))
        assertEquals(AnalysisStatus.ANALYZING, state.snapshot().analysisStatus)
        assertEquals(VerificationStatus.IDLE, state.snapshot().verificationStatus)
    }

    @Test
    fun `only one caller can claim a ready revision`() {
        val state = AffectedStateStore()
        val revision = state.invalidate()
        assertTrue(state.complete(revision, listOf(module(":ready"))))

        assertTrue(state.tryMarkRunning(revision))
        assertFalse(state.tryMarkRunning(revision))
        assertEquals(VerificationStatus.RUNNING, state.snapshot().verificationStatus)
    }

    private fun module(id: String) = AffectedModule(
        id = id,
        systemId = "GRADLE",
        buildRoot = "/repo",
        directory = "/repo$id",
        testDirectory = null,
        testTask = "test",
        compileTask = null,
        hasTests = true,
        tasks = emptySet(),
    )
}
