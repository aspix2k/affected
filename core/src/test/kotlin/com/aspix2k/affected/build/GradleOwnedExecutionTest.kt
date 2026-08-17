package com.aspix2k.affected.build

import com.aspix2k.affected.OwnedExternalTaskExecution
import com.aspix2k.affected.monitorGradleCancellation
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GradleOwnedExecutionTest {

    private val buildSystem = GradleBuildSystem()

    @Test
    fun `Affected Gradle execution keeps a bounded name without changing tasks`() {
        val tasks = List(128) { index -> ":feature-with-a-long-name-$index:testDebugUnitTest" }
        val settings = buildSystem.gradleTaskExecutionSettings("/fixture", tasks, listOf("--continue"))
        val execution = OwnedExternalTaskExecution(cancelTask = { true })

        val specification = buildSystem.gradleTaskExecutionSpec(
            project(),
            settings,
            execution.listener,
            execution.callback,
        )

        assertEquals("Affected", specification.settings.executionName)
        assertEquals("/fixture", specification.settings.externalProjectPath)
        assertEquals(tasks, specification.settings.taskNames)
        assertEquals("--continue", specification.settings.scriptParameters)
        assertEquals("GRADLE", specification.settings.externalSystemIdString)
    }

    @Test
    fun `awaited Gradle execution attaches its exact owned listener`() {
        val execution = OwnedExternalTaskExecution(cancelTask = { true })
        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = "/fixture"
            taskNames = listOf(":test")
        }

        val specification = buildSystem.gradleTaskExecutionSpec(
            project(),
            settings,
            execution.listener,
            execution.callback,
        )

        assertSame(execution.listener, specification.listener)
        assertSame(execution.callback, specification.callback)
        assertSame(settings, specification.settings)
        assertEquals(ProgressExecutionMode.NO_PROGRESS_SYNC, specification.progressExecutionMode)
    }

    @Test
    fun `Affected Gradle execution carries the aggregate binding into its run profile`() {
        val execution = OwnedExternalTaskExecution(cancelTask = { true })
        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = "/fixture"
            taskNames = listOf(":test")
        }
        val binding = UserDataHolderBase()

        val specification = buildSystem.gradleTaskExecutionSpec(
            project(),
            settings,
            execution.listener,
            execution.callback,
            binding,
        )

        assertSame(binding, specification.userData)
    }

    @Test
    fun `Gradle cancellation waits for accepted cancellation and exact task termination`() {
        val scheduled = ArrayDeque<() -> Unit>()
        var attempts = 0
        var terminated = false
        var completionCalls = 0

        assertTrue(
            monitorGradleCancellation(
                cancel = { ++attempts > 1 },
                terminated = { terminated },
                onTerminated = { completionCalls++ },
                schedule = { _, action -> scheduled.add(action); true },
            ),
        )
        assertEquals(0, attempts)

        scheduled.removeFirst().invoke()
        assertEquals(1, attempts)
        assertEquals(0, completionCalls)

        scheduled.removeFirst().invoke()
        assertEquals(2, attempts)
        assertEquals(0, completionCalls)

        terminated = true
        scheduled.removeFirst().invoke()
        assertEquals(1, completionCalls)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `Gradle cancellation keeps terminal monitoring after cancel attempts are exhausted`() {
        val scheduled = ArrayDeque<() -> Unit>()
        var attempts = 0
        var terminated = false
        var completionCalls = 0
        var exhaustedCalls = 0

        assertTrue(
            monitorGradleCancellation(
                cancel = { attempts++; false },
                terminated = { terminated },
                onTerminated = { completionCalls++ },
                onCancelAttemptsExhausted = { exhaustedCalls++ },
                schedule = { _, action -> scheduled.add(action); true },
            ),
        )
        repeat(64) {
            scheduled.removeFirst().invoke()
        }
        assertEquals(64, attempts)
        assertEquals(0, completionCalls)
        assertEquals(1, exhaustedCalls)
        assertFalse(scheduled.isEmpty())

        scheduled.removeFirst().invoke()
        assertEquals(64, attempts)
        assertEquals(0, completionCalls)
        assertEquals(1, exhaustedCalls)
        terminated = true
        scheduled.removeFirst().invoke()

        assertEquals(1, completionCalls)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `Gradle cancellation reports a later terminal scheduling failure`() {
        val scheduled = ArrayDeque<() -> Unit>()
        var schedulingCalls = 0
        var stoppedCalls = 0

        assertTrue(
            monitorGradleCancellation(
                cancel = { false },
                terminated = { false },
                onTerminated = {},
                onMonitoringStopped = { stoppedCalls++ },
                schedule = { _, action ->
                    schedulingCalls++
                    if (schedulingCalls == 1) scheduled.add(action)
                    schedulingCalls == 1
                },
            ),
        )
        scheduled.removeFirst().invoke()

        assertEquals(2, schedulingCalls)
        assertEquals(1, stoppedCalls)
        assertTrue(scheduled.isEmpty())
    }

    private fun project(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ -> error("Unexpected Project call: ${method.name}") } as Project
}
