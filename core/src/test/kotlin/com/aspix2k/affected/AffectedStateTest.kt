package com.aspix2k.affected

import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import java.lang.reflect.Proxy
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

class AffectedStateTest {

    @Test
    fun `status stays running until every run finishes`() {
        val state = AffectedState(project(), CoroutineScope(EmptyCoroutineContext))

        state.markRunning()
        state.markRunning()
        state.markFinished()

        assertEquals(VerificationStatus.RUNNING, state.verificationStatus)

        state.markFinished()

        assertEquals(VerificationStatus.IDLE, state.verificationStatus)
    }

    @Test
    fun `a completed run returns to idle`() {
        val state = AffectedState(project(), CoroutineScope(EmptyCoroutineContext))

        state.markRunning()
        state.markFinished()

        assertEquals(VerificationStatus.IDLE, state.verificationStatus)
    }

    private fun project(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ -> error("Unexpected Project call: ${method.name}") } as Project
}
