package com.aspix2k.affected

import com.aspix2k.affected.build.GradleBuildSystem
import com.aspix2k.affected.build.gradleExecutionCoordinates
import com.aspix2k.affected.build.gradleExecutionMetadata
import com.aspix2k.affected.build.gradleProjectPath
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.ModuleData
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GradleTaskPathTest {

    @Test
    fun `owning module metadata keeps source sets in one composite invocation`() {
        val modules = listOf(
            moduleInfo("/repo/platform", ":shared-data", ":platform:shared-data"),
            moduleInfo("/repo/store", ":ui-shell", ":store:ui-shell"),
        )
        val plan = TaskPlanner.plan(modules, emptyList())

        assertEquals(1, plan.groups.size)
        assertEquals("/repo", plan.groups.single().root)
        assertEquals(
            listOf(":platform:shared-data:test", ":store:ui-shell:test"),
            plan.groups.single().tasks,
        )
    }

    @Test
    fun `module discovery uses the owning module index instead of the source set index`() {
        val bytecode = GradleBuildSystem::class.java
            .getResourceAsStream("GradleBuildSystem.class")
            ?.use { it.readBytes().toString(Charsets.ISO_8859_1) }
            ?: error("GradleBuildSystem bytecode is missing")

        assertContains(bytecode, "ExternalSystemModuleDataIndex")
        assertFalse(bytecode.contains("GradleModuleDataIndex"))
        assertFalse(bytecode.contains("GradleModuleDataKt"))
    }

    @Test
    fun `an included build uses the composite execution coordinates`() {
        assertEquals(
            "/repo" to ":platform:shared-ui",
            gradleExecutionCoordinates(
                ownerRoot = "/repo/platform",
                ownerId = ":shared-ui",
                directoryToRunTask = "/repo",
                identityPath = ":platform:shared-ui",
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
            "/repo" to ":legacy-renamed:ui-shell",
            gradleExecutionCoordinates(
                "/repo/legacy",
                ":ui-shell",
                "/repo",
                ":legacy-renamed:ui-shell",
            ),
        )
    }

    @Test
    fun `a source set model keeps its Gradle identity path`() {
        assertEquals(
            "/repo" to ":platform:shared-data",
            gradleExecutionCoordinates(
                "/repo/platform",
                ":shared-data",
                "/repo",
                ":platform:shared-data",
            ),
        )
    }

    @Test
    fun `incomplete Gradle execution data falls back to owning coordinates`() {
        assertEquals(
            "/repo/platform" to ":shared-data",
            gradleExecutionCoordinates(
                "/repo/platform",
                ":shared-data",
                "/repo",
                null,
            ),
        )
        assertEquals(
            "/repo/platform" to ":shared-data",
            gradleExecutionCoordinates(
                "/repo/platform",
                ":shared-data",
                null,
                ":platform:shared-data",
            ),
        )
    }

    @Test
    fun `a composite build identity path becomes a path inside its build root`() {
        assertEquals(":shared-data", gradleProjectPath(":platform:shared-data:main", "platform", true))
    }

    @Test
    fun `a nested Gradle path is preserved`() {
        assertEquals(":ui:flow", gradleProjectPath(":ui:flow:test", null, true))
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

    private fun moduleInfo(ownerRoot: String, ownerId: String, identityPath: String): ModuleInfo {
        val moduleData = ModuleData(
            ownerId,
            ProjectSystemId("GRADLE"),
            "JAVA_MODULE",
            ownerId,
            "$ownerRoot/${ownerId.removePrefix(":")}",
            "$ownerRoot/${ownerId.removePrefix(":")}",
        ).apply {
            setProperty("directoryToRunTask", "/repo")
            setProperty("gradleIdentityPath", identityPath)
        }
        val metadata = gradleExecutionMetadata(moduleData)
        val (executionRoot, executionId) = gradleExecutionCoordinates(
            ownerRoot,
            ownerId,
            metadata.first,
            metadata.second,
        )
        return ModuleInfo(
            id = ownerId,
            systemId = "GRADLE",
            buildRoot = ownerRoot,
            testTask = "test",
            compileTask = "compileTestKotlin",
            hasTests = true,
            executionRoot = executionRoot,
            executionId = executionId,
        )
    }
}
