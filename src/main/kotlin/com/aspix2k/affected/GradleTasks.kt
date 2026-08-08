package com.aspix2k.affected

import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants

class GradleTasks(private val project: Project) {

    fun namesFor(modulePath: String): Set<String> = tasksByModule()[modulePath].orEmpty()

    fun hasTask(modulePath: String, name: String): Boolean = namesFor(modulePath).contains(name)

    private fun tasksByModule(): Map<String, Set<String>> {
        val result = HashMap<String, MutableSet<String>>()

        for (settings in GradleSettings.getInstance(project).linkedProjectsSettings) {
            val projectNode = ExternalSystemApiUtil.findProjectNode(
                project,
                GradleConstants.SYSTEM_ID,
                settings.externalProjectPath,
            ) ?: continue

            for (moduleNode in ExternalSystemApiUtil.findAll(projectNode, ProjectKeys.MODULE)) {
                val module: ModuleData = moduleNode.data
                val names = result.getOrPut(module.linkedExternalProjectPath) { mutableSetOf() }
                for (taskNode in ExternalSystemApiUtil.findAll(moduleNode, ProjectKeys.TASK)) {
                    val task: TaskData = taskNode.data
                    names += task.name.substringAfterLast(':')
                }
            }
        }
        return result
    }
}
