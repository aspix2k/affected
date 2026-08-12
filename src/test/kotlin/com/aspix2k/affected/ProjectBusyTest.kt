package com.aspix2k.affected

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectBusyTest {

    @Test
    fun `project is busy for sync discovery and build tasks`() {
        assertTrue(externalSystemBusy { it == ExternalSystemTaskType.RESOLVE_PROJECT })
        assertTrue(externalSystemBusy { it == ExternalSystemTaskType.REFRESH_TASKS_LIST })
        assertTrue(externalSystemBusy { it == ExternalSystemTaskType.EXECUTE_TASK })
        assertFalse(externalSystemBusy { false })
    }
}
