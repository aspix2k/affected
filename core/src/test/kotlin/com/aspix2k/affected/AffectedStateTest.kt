package com.aspix2k.affected

import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    ) { _, method, _ -> error("Unexpected Project call: ${method.name}") } as Project
}
