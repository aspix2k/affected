package com.aspix2k.affected

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalSystemRefreshListenerTest {

    @Test
    fun `external task completion invalidates its live project once`() {
        var invalidations = 0

        invalidateAfterExternalTask(project(disposed = false)) { invalidations++ }

        assertEquals(1, invalidations)
    }

    @Test
    fun `a proven remote frontend does not invalidate after an external task`() {
        var invalidations = 0

        invalidateAfterExternalTask(project(disposed = false), frontend = true) { invalidations++ }

        assertEquals(0, invalidations)
    }

    @Test
    fun `missing or disposed projects are ignored`() {
        var invalidations = 0

        invalidateAfterExternalTask(null) { invalidations++ }
        invalidateAfterExternalTask(project(disposed = true)) { invalidations++ }

        assertEquals(0, invalidations)
    }

    @Test
    fun `plugin descriptor keeps only refresh invalidation as a global external listener`() {
        val descriptor = javaClass.classLoader
            .getResourceAsStream("META-INF/plugin.xml")
            ?.bufferedReader()
            ?.readText()
            ?: error("plugin.xml is missing")

        assertEquals(true, descriptor.contains("com.aspix2k.affected.ExternalSystemRefreshListener"))
        assertEquals(false, descriptor.contains("com.aspix2k.affected.AffectedExecuteTaskListener"))
    }

    @Test
    fun `only model-changing external tasks require a new plan`() {
        assertEquals(true, externalTaskRefreshesModel(ExternalSystemTaskType.RESOLVE_PROJECT))
        assertEquals(true, externalTaskRefreshesModel(ExternalSystemTaskType.REFRESH_TASKS_LIST))
        assertEquals(false, externalTaskRefreshesModel(ExternalSystemTaskType.EXECUTE_TASK))
    }

    private fun project(disposed: Boolean): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        if (method.name == "isDisposed") disposed else error("Unexpected Project call: ${method.name}")
    } as Project
}
