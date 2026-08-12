package com.aspix2k.affected

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AffectedUiStateTest {

    @Test
    fun `sync and invalidation show analysis instead of stale ready data`() {
        assertEquals(
            AffectedUiState.ANALYZING,
            affectedUiState(AnalysisStatus.READY, VerificationStatus.IDLE, ideBusy = true, affectedModules = 1),
        )
        assertEquals(
            AffectedUiState.ANALYZING,
            affectedUiState(AnalysisStatus.ANALYZING, VerificationStatus.IDLE, ideBusy = false, affectedModules = 1),
        )
    }

    @Test
    fun `only a current nonempty analysis can run`() {
        val ready = affectedUiState(
            AnalysisStatus.READY,
            VerificationStatus.IDLE,
            ideBusy = false,
            affectedModules = 2,
        )
        val empty = affectedUiState(
            AnalysisStatus.READY,
            VerificationStatus.IDLE,
            ideBusy = false,
            affectedModules = 0,
        )

        assertEquals(AffectedUiState.READY, ready)
        assertTrue(ready.canRun)
        assertEquals("group.title", ready.groupTitleKey)
        assertEquals("action.run.text", ready.runActionTextKey)
        assertEquals(AffectedUiState.EMPTY, empty)
        assertFalse(empty.canRun)
    }

    @Test
    fun `running and unavailable states cannot start another plan`() {
        val running = affectedUiState(
            AnalysisStatus.READY,
            VerificationStatus.RUNNING,
            ideBusy = false,
            affectedModules = 2,
        )
        val unavailable = affectedUiState(
            AnalysisStatus.UNAVAILABLE,
            VerificationStatus.IDLE,
            ideBusy = false,
            affectedModules = 0,
        )

        assertEquals(AffectedUiState.RUNNING, running)
        assertTrue(running.animated)
        assertFalse(running.canRun)
        assertEquals(AffectedUiState.UNAVAILABLE, unavailable)
        assertFalse(unavailable.canRun)
    }
}
