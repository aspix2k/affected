package com.aspix2k.affected

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AffectedExecuteTaskListenerTest {

    @Test
    fun `a local execute task is claimed`() {
        assertTrue(shouldClaimExternalExecute(ExternalSystemTaskType.EXECUTE_TASK, frontend = false))
    }

    @Test
    fun `a proven remote frontend does not claim an execute task`() {
        assertFalse(shouldClaimExternalExecute(ExternalSystemTaskType.EXECUTE_TASK, frontend = true))
    }

    @Test
    fun `resolve and refresh tasks are not execute claims`() {
        assertFalse(shouldClaimExternalExecute(ExternalSystemTaskType.RESOLVE_PROJECT, frontend = false))
        assertFalse(shouldClaimExternalExecute(ExternalSystemTaskType.REFRESH_TASKS_LIST, frontend = false))
    }
}
