package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AffectedStateTest {

    @Test
    fun `a project claim is owned before it can be dispatched`() {
        val sessions = AffectedRunSessions()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = AffectedState(
            project = project(sessions),
            scope = scope,
            debounceMs = 0,
            awaitSmart = {},
            analyzeProject = { error("analysis is not expected") },
        )
        try {
            val claim = requireNotNull(state.tryClaimVerification())

            assertEquals(1, sessions.activeCount())
            assertEquals(1, sessions.stopOwned())
            assertFalse(claim.markRunning())
            claim.close()
            assertEquals(0, sessions.activeCount())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an oversized published snapshot fails closed`() {
        val state = AffectedStateStore()
        val revision = state.invalidate()
        val modules = List(MAX_PUBLISHED_MODULES + 1) { index -> module(":$index") }

        assertEquals(false, state.complete(revision, modules))
        assertEquals(AnalysisStatus.UNAVAILABLE, state.snapshot().analysisStatus)
        assertEquals(emptyList(), state.snapshot().modules)
    }

    @Test
    fun `the toolbar count is modules that will run tests`() {
        val snapshot = AffectedStateSnapshot(
            revision = 1,
            analysisStatus = AnalysisStatus.READY,
            modules = listOf(
                module(":ui").copy(hasTests = true),
                module(":auth").copy(hasTests = false, compileTask = "compileKotlinMetadata"),
                module(":prefs").copy(hasTests = false, compileTask = "compileKotlinMetadata"),
            ),
            verificationStatus = VerificationStatus.IDLE,
        )

        assertEquals(1, snapshot.affectedModules)
        assertEquals(3, snapshot.modules.size)
    }

    @Test
    fun `a snapshot at the published module budget stays ready`() {
        val state = AffectedStateStore()
        val revision = state.invalidate()
        val modules = List(MAX_PUBLISHED_MODULES) { index -> module(":$index") }

        assertEquals(true, state.complete(revision, modules))
        assertEquals(AnalysisStatus.READY, state.snapshot().analysisStatus)
        assertEquals(MAX_PUBLISHED_MODULES, state.snapshot().modules.size)
    }

    @Test
    fun `verification claims are exclusive`() {
        val state = AffectedStateStore()
        val revision = state.invalidate()
        state.complete(revision, listOf(module()))
        val claim = state.tryClaimVerification()

        assertEquals(VerificationStatus.PREPARING, state.snapshot().verificationStatus)
        assertEquals(null, state.tryClaimVerification())
        assertEquals(null, state.tryClaimReadyRun())
        claim?.markRunning()
        assertEquals(VerificationStatus.RUNNING, state.snapshot().verificationStatus)

        claim?.close()

        assertEquals(VerificationStatus.IDLE, state.snapshot().verificationStatus)
    }

    @Test
    fun `an action claim blocks ordinary verification`() {
        val state = AffectedStateStore()
        val revision = state.invalidate()
        state.complete(revision, listOf(module()))
        val claim = state.tryClaimReadyRun()

        assertEquals(VerificationStatus.PREPARING, state.snapshot().verificationStatus)
        assertEquals(null, state.tryClaimVerification())

        claim?.close()
    }

    @Test
    fun `a canceled analysis retries instead of stopping refreshes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var attempts = 0
        val state = AffectedState(
            project = project(),
            scope = scope,
            debounceMs = 0,
            awaitSmart = {},
            analyzeProject = {
                attempts++
                if (attempts == 1) throw ProcessCanceledException()
                AffectedAnalysis(
                    modules = emptyList(),
                    changes = ProjectChanges.Result(emptyList(), emptySet(), emptySet(), comparedToBase = true),
                    plans = emptyPlans(),
                )
            },
        )
        try {
            state.invalidate()
            withTimeout(1_000) {
                while (!state.ready) yield()
            }

            assertEquals(2, attempts)
            assertEquals(AnalysisStatus.READY, state.analysisStatus)
        } finally {
            scope.cancel()
        }
    }

    private fun module(id: String = ":ready") = AffectedModule(
        id = id,
        systemId = "GRADLE",
        buildRoot = "/repo",
        directory = "/repo/ready",
        testDirectory = null,
        testTask = "test",
        compileTask = null,
        hasTests = true,
        tasks = emptySet(),
    )

    private fun emptyPlans(): Verification.PreparedPlans {
        val prepared = Verification.Prepared(
            Plan(emptyList(), 0, 0),
            BuildChanges(emptyList(), emptySet(), comparedToBase = true),
        )
        return Verification.PreparedPlans(prepared, prepared)
    }

    private fun project(sessions: AffectedRunSessions? = null): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "isDisposed" -> false
            "getService" -> sessions
            else -> error("Unexpected Project call: ${method.name}")
        }
    } as Project
}
