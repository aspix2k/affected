package com.aspix2k.affected

import com.aspix2k.affected.build.gradleExecutionMetadata
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.internal.InternalExternalProjectInfo
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.ExternalSystemModuleDataIndex
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsDataStorage
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData
import org.jetbrains.plugins.gradle.util.GradleConstants

class GradleModuleModelRegressionTest : BasePlatformTestCase() {

    fun testSourceSetResolvesOwningCompositeModuleData() {
        val systemId = GradleConstants.SYSTEM_ID
        val projectData = ProjectData(systemId, "fixture", "/repo", "/repo")
        val projectNode = DataNode(ProjectKeys.PROJECT, projectData, null)
        val ownerData = ModuleData(
            ":platform:shared-data",
            systemId,
            "JAVA_MODULE",
            "shared-data",
            "/repo/platform/shared-data",
            "/repo/platform/shared-data",
        ).apply {
            setProperty("directoryToRunTask", "/repo")
            setProperty("gradleIdentityPath", ":platform:shared-data")
        }
        val ownerNode = projectNode.createChild(ProjectKeys.MODULE, ownerData)
        ownerNode.createChild(
            GradleSourceSetData.KEY,
            GradleSourceSetData(
                ":platform:shared-data:test",
                ":platform:shared-data:test",
                "shared-data.test",
                "/repo/platform/shared-data",
                "/repo/platform/shared-data",
            ),
        )
        ExternalProjectsDataStorage.getInstance(project).update(
            InternalExternalProjectInfo(systemId, "/repo", projectNode),
        )

        val resolved = ExternalSystemModuleDataIndex.findModuleNode(
            project,
            ownerData.linkedExternalProjectPath,
        )?.data

        assertEquals(
            "/repo" to ":platform:shared-data",
            gradleExecutionMetadata(resolved),
        )
    }
}
