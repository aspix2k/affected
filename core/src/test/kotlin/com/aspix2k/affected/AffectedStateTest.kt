package com.aspix2k.affected

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

class AffectedStateTest {

    @Test
    fun `verification claims are exclusive`() {
        val state = AffectedStateStore()
        val revision = state.invalidate()
        state.complete(revision, listOf(module()))
        val claim = state.tryClaimVerification()

        assertEquals(VerificationStatus.RUNNING, state.snapshot().verificationStatus)
        assertEquals(null, state.tryClaimVerification())
        assertEquals(null, state.tryClaimReadyRun())

        claim?.close()

        assertEquals(VerificationStatus.IDLE, state.snapshot().verificationStatus)
    }

    @Test
    fun `an action claim blocks ordinary verification`() {
        val state = AffectedStateStore()
        val revision = state.invalidate()
        state.complete(revision, listOf(module()))
        val claim = state.tryClaimReadyRun()

        assertEquals(VerificationStatus.RUNNING, state.snapshot().verificationStatus)
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
                emptyList()
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

    private fun module() = AffectedModule(
        id = ":ready",
        systemId = "GRADLE",
        buildRoot = "/repo",
        directory = "/repo/ready",
        testDirectory = null,
        testTask = "test",
        compileTask = null,
        hasTests = true,
        tasks = emptySet(),
    )

    private fun project(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        if (method.name == "isDisposed") false else error("Unexpected Project call: ${method.name}")
    } as Project
}
