package com.aspix2k.affected

import com.aspix2k.affected.build.gradleProjectPath
import kotlin.test.Test
import kotlin.test.assertEquals

class GradleTaskPathTest {

    @Test
    fun `a composite build identity path becomes a path inside its build root`() {
        assertEquals(":generic-screen-data", gradleProjectPath(":features:generic-screen-data:main", "features", true))
    }

    @Test
    fun `a nested Gradle path is preserved`() {
        assertEquals(":generic-screen:data", gradleProjectPath(":generic-screen:data:test", null, true))
    }

    @Test
    fun `a regular project id without a leading colon is supported`() {
        assertEquals(":core", gradleProjectPath("root:core:main", "root", true))
    }

    @Test
    fun `a root source set runs a root project task`() {
        assertEquals("", gradleProjectPath(":features:main", "features", true))
    }

    @Test
    fun `a project named test is not mistaken for a source set`() {
        assertEquals(":test", gradleProjectPath(":test", null, false))
    }
}
