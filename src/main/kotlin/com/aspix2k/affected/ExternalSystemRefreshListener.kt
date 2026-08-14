package com.aspix2k.affected

import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.project.Project

class ExternalSystemRefreshListener : ExternalSystemTaskNotificationListener {

    override fun onEnd(projectPath: String, id: ExternalSystemTaskId) {
        if (!externalTaskRefreshesModel(id.type)) return
        invalidateAfterExternalTask(id.findProject()) { it.service<AffectedState>().invalidate() }
    }
}

internal fun externalTaskRefreshesModel(type: ExternalSystemTaskType): Boolean =
    type == ExternalSystemTaskType.RESOLVE_PROJECT || type == ExternalSystemTaskType.REFRESH_TASKS_LIST

internal fun invalidateAfterExternalTask(
    project: Project?,
    frontend: Boolean = remoteFrontendProven(),
    invalidate: (Project) -> Unit,
) {
    if (frontend) return
    if (project != null && !project.isDisposed) invalidate(project)
}
