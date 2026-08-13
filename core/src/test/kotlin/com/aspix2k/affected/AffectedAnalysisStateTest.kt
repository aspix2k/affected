package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
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
    fun `an invalidated result cannot be claimed before fresh analysis`() {
        val state = AffectedStateStore()
        val revision = state.invalidate()
        assertTrue(state.complete(revision, listOf(module(":ready"))))

        val latest = state.invalidate()

        assertEquals(null, state.tryClaimReadyRun())
        assertEquals(AnalysisStatus.ANALYZING, state.snapshot().analysisStatus)
        assertEquals(VerificationStatus.IDLE, state.snapshot().verificationStatus)
        assertTrue(state.complete(latest, listOf(module(":fresh"))))
        val claim = state.tryClaimReadyRun()
        assertEquals(listOf(":fresh"), claim?.snapshot?.modules?.map(AffectedModule::id))
        claim?.close()
    }

    @Test
    fun `only one caller can claim a ready revision`() {
        val state = AffectedStateStore()
        val revision = state.invalidate()
        assertTrue(state.complete(revision, analysis(":ready")))

        val claim = state.tryClaimReadyRun()
        assertTrue(claim != null)
        assertEquals(VerificationStatus.PREPARING, state.snapshot().verificationStatus)
        assertEquals(null, state.tryClaimReadyRun())
        assertEquals(null, state.tryClaimVerification())
        assertTrue(claim.markRunning())
        assertEquals(VerificationStatus.RUNNING, state.snapshot().verificationStatus)
        claim.close()
        claim.close()
        assertEquals(VerificationStatus.IDLE, state.snapshot().verificationStatus)
    }

    @Test
    fun `a ready claim keeps the changes collected by its exact revision`() {
        val state = AffectedStateStore()
        val first = state.invalidate()
        assertTrue(state.complete(first, analysis(":first", "/repo/first.kt")))
        val stale = requireNotNull(state.tryClaimReadyRun())
        stale.close()

        val second = state.invalidate()
        assertTrue(state.complete(second, analysis(":second", "/repo/second.kt")))
        val current = requireNotNull(state.tryClaimReadyRun())

        assertEquals(
            listOf(java.io.File("/repo/second.kt")),
            requireNotNull(current.changes).files,
        )
        current.close()
    }

    @Test
    fun `a ready claim reuses the verification plan prepared by its exact revision`() {
        val state = AffectedStateStore()
        val revision = state.invalidate()
        val analysis = analysis(":ready")
        assertTrue(state.complete(revision, analysis))

        val first = requireNotNull(state.tryClaimReadyRun())
        val prepared = first.prepared
        first.close()
        val second = requireNotNull(state.tryClaimReadyRun())

        assertSame(prepared, second.prepared)
        second.close()
    }

    @Test
    fun `an invalidation during preparation prevents a stale run`() {
        val state = AffectedStateStore()
        val revision = state.invalidate()
        assertTrue(state.complete(revision, analysis(":ready")))
        val claim = requireNotNull(state.tryClaimReadyRun())

        state.invalidate()

        assertFalse(claim.markRunning())
        claim.close()
        assertEquals(VerificationStatus.IDLE, state.snapshot().verificationStatus)
    }

    @Test
    fun `a cancelled scope releases a claim before the coroutine body starts`() = runBlocking {
        val state = AffectedStateStore()
        val revision = state.invalidate()
        assertTrue(state.complete(revision, analysis(":ready")))
        val claim = requireNotNull(state.tryClaimReadyRun())
        val cancelled = Job().apply { cancel() }
        var entered = false

        val job = launchClaimed(claim, { CoroutineScope(cancelled) }) { entered = true }
        job.join()

        assertFalse(entered)
        assertEquals(VerificationStatus.IDLE, state.snapshot().verificationStatus)
    }

    @Test
    fun `failure to obtain an action scope releases a claim`() {
        val state = AffectedStateStore()
        val revision = state.invalidate()
        assertTrue(state.complete(revision, analysis(":ready")))
        val claim = requireNotNull(state.tryClaimReadyRun())

        assertFailsWith<IllegalStateException> {
            launchClaimed(claim, { error("scope unavailable") }) {}
        }

        assertEquals(VerificationStatus.IDLE, state.snapshot().verificationStatus)
    }

    @Test
    fun `completed snapshot does not retain a mutable module list`() {
        val state = AffectedStateStore()
        val revision = state.invalidate()
        val modules = mutableListOf(module(":ready"))

        assertTrue(state.complete(revision, modules))
        modules.clear()

        assertEquals(listOf(":ready"), state.snapshot().modules.map(AffectedModule::id))
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

    private fun analysis(id: String, path: String = "/repo/ready.kt"): AffectedAnalysis {
        val changes = ProjectChanges.Result(
            files = listOf(java.io.File(path)),
            apiTouched = emptySet(),
            exactSelectionEligible = emptySet(),
            comparedToBase = true,
        )
        val prepared = Verification.Prepared(
            plan = Plan(emptyList(), tested = 0, compiled = 0),
            changes = BuildChanges(emptyList(), emptySet(), comparedToBase = true),
        )
        return AffectedAnalysis(
            modules = listOf(module(id)),
            changes = changes,
            plans = Verification.PreparedPlans(prepared, prepared),
        )
    }
}
