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
    fun `status stays running until every run finishes`() {
        withState { state ->
            state.markRunning()
            state.markRunning()
            state.markFinished()

            assertEquals(VerificationStatus.RUNNING, state.verificationStatus)

            state.markFinished()

            assertEquals(VerificationStatus.IDLE, state.verificationStatus)
        }
    }

    @Test
    fun `a completed run returns to idle`() {
        withState { state ->
            state.markRunning()
            state.markFinished()

            assertEquals(VerificationStatus.IDLE, state.verificationStatus)
        }
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

    private fun withState(block: (AffectedState) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            block(AffectedState(project(), scope))
        } finally {
            scope.cancel()
        }
    }

    private fun project(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        if (method.name == "isDisposed") false else error("Unexpected Project call: ${method.name}")
    } as Project
}
