package com.aspix2k.affected

import com.intellij.openapi.actionSystem.Presentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AffectedToolbarPresentationTest {

    @Test
    fun `invalidation replaces a ready toolbar with the analyzing presentation`() {
        val presentation = Presentation()
        presentAffectedGroup(
            presentation = presentation,
            snapshot = snapshot(AnalysisStatus.ANALYZING, modules = listOf(module())),
            ideBusy = false,
            animateWhileRunning = true,
            projectAvailable = true,
        )
        val run = Presentation()
        presentRunAffectedAction(
            run,
            snapshot(AnalysisStatus.ANALYZING, modules = listOf(module())),
            ideBusy = false,
        )

        assertTrue(presentation.isEnabled)
        assertEquals(AffectedIcons.Running, presentation.icon)
        assertEquals(AffectedBundle.message("action.run.description.counting"), presentation.description)
        assertFalse(run.isEnabled)
        assertEquals(AffectedBundle.message("action.run.description.counting"), run.description)
    }

    @Test
    fun `a completed analysis shows the current toolbar count`() {
        val presentation = Presentation()
        presentAffectedGroup(
            presentation = presentation,
            snapshot = snapshot(AnalysisStatus.READY, modules = listOf(module(), module(":two"))),
            ideBusy = false,
            animateWhileRunning = true,
            projectAvailable = true,
        )
        val run = Presentation()
        presentRunAffectedAction(
            run,
            snapshot(AnalysisStatus.READY, modules = listOf(module(), module(":two"))),
            ideBusy = false,
        )

        assertEquals(AffectedIcons.withCount(2), presentation.icon)
        assertTrue(run.isEnabled)
        assertEquals(AffectedBundle.message("action.run.description"), run.description)
    }

    @Test
    fun `an unavailable analysis disables run without a stale count`() {
        val presentation = Presentation()
        presentAffectedGroup(
            presentation = presentation,
            snapshot = snapshot(AnalysisStatus.UNAVAILABLE),
            ideBusy = false,
            animateWhileRunning = true,
            projectAvailable = true,
        )
        val run = Presentation()
        presentRunAffectedAction(run, snapshot(AnalysisStatus.UNAVAILABLE), ideBusy = false)

        assertEquals(AffectedIcons.Action, presentation.icon)
        assertEquals(AffectedBundle.message("notification.unresolved.title"), presentation.description)
        assertFalse(run.isEnabled)
        assertEquals(AffectedBundle.message("notification.unresolved.title"), run.description)
    }

    @Test
    fun `a missing project disables the run action and leaves the module popup unread`() {
        val presentation = Presentation()
        presentAffectedGroup(
            presentation = presentation,
            snapshot = null,
            ideBusy = false,
            animateWhileRunning = true,
            projectAvailable = false,
        )
        val run = Presentation()
        presentRunAffectedAction(run, snapshot = null, ideBusy = false)

        assertFalse(presentation.isEnabled)
        assertEquals(AffectedIcons.Action, presentation.icon)
        assertNull(presentation.description)
        assertFalse(run.isEnabled)
    }

    private fun snapshot(
        analysisStatus: AnalysisStatus,
        modules: List<AffectedModule> = emptyList(),
        verificationStatus: VerificationStatus = VerificationStatus.IDLE,
    ) = AffectedStateSnapshot(
        revision = 1,
        analysisStatus = analysisStatus,
        modules = modules,
        verificationStatus = verificationStatus,
    )

    private fun module(id: String = ":one") = AffectedModule(
        id = id,
        systemId = "GRADLE",
        buildRoot = "/repo",
        directory = "/repo/one",
        testDirectory = null,
        testTask = "test",
        compileTask = null,
        hasTests = true,
        tasks = emptySet(),
    )
}
