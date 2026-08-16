package com.aspix2k.affected

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class GitRepositoryRefreshListenerTest {

    @Test
    fun `a Git repository refresh invalidates its live project`() {
        var invalidations = 0

        invalidateAfterGitRepositoryRefresh(project(disposed = false)) { invalidations++ }

        assertEquals(1, invalidations)
    }

    @Test
    fun `a proven remote frontend ignores Git repository refreshes`() {
        var invalidations = 0

        invalidateAfterGitRepositoryRefresh(project(disposed = false), frontend = true) { invalidations++ }

        assertEquals(0, invalidations)
    }

    @Test
    fun `a disposed project ignores Git repository refreshes`() {
        var invalidations = 0

        invalidateAfterGitRepositoryRefresh(project(disposed = true), frontend = false) { invalidations++ }

        assertEquals(0, invalidations)
    }

    private fun project(disposed: Boolean): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        if (method.name == "isDisposed") disposed else error("Unexpected Project call: ${method.name}")
    } as Project
}
