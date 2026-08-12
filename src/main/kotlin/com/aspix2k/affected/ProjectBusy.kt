package com.aspix2k.affected

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project

internal fun projectBusy(project: Project): Boolean =
    DumbService.isDumb(project) || runCatching {
        val manager = ExternalSystemProcessingManager.getInstance()
        externalSystemBusy { manager.hasTaskOfTypeInProgress(it, project) }
    }.getOrDefault(false)

internal fun externalSystemBusy(inProgress: (ExternalSystemTaskType) -> Boolean): Boolean =
    BUSY_EXTERNAL_TASKS.any(inProgress)

private val BUSY_EXTERNAL_TASKS = ExternalSystemTaskType.entries
