package com.aspix2k.affected

import com.aspix2k.affected.build.gradleExecutionCoordinates
import com.aspix2k.affected.build.gradleProjectPath
import kotlin.test.Test
import kotlin.test.assertEquals

class GradleTaskPathTest {

    @Test
    fun `an included build uses the composite execution coordinates`() {
        assertEquals(
            "/repo" to ":features:generic-screen-flow-ui",
            gradleExecutionCoordinates(
                ownerRoot = "/repo/features",
                ownerId = ":generic-screen-flow-ui",
                directoryToRunTask = "/repo",
                identityPath = ":features:generic-screen-flow-ui",
            ),
        )
    }

    @Test
    fun `the Gradle root identity becomes an empty task prefix`() {
        assertEquals(
            "/repo" to "",
            gradleExecutionCoordinates("/repo", "", "/repo", ":"),
        )
    }

    @Test
    fun `a renamed included build identity is preserved`() {
        assertEquals(
            "/repo" to ":market-renamed:app-integration",
            gradleExecutionCoordinates(
                "/repo/magnit-market",
                ":app-integration",
                "/repo",
                ":market-renamed:app-integration",
            ),
        )
    }

    @Test
    fun `a source set model keeps its Gradle identity path`() {
        assertEquals(
            "/repo" to ":features:generic-screen-data",
            gradleExecutionCoordinates(
                "/repo/features",
                ":generic-screen-data",
                "/repo",
                ":features:generic-screen-data",
            ),
        )
    }

    @Test
    fun `incomplete Gradle execution data falls back to owning coordinates`() {
        assertEquals(
            "/repo/features" to ":generic-screen-data",
            gradleExecutionCoordinates(
                "/repo/features",
                ":generic-screen-data",
                "/repo",
                null,
            ),
        )
        assertEquals(
            "/repo/features" to ":generic-screen-data",
            gradleExecutionCoordinates(
                "/repo/features",
                ":generic-screen-data",
                null,
                ":features:generic-screen-data",
            ),
        )
    }

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
