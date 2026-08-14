package com.aspix2k.affected

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager

class AffectedExecuteTaskListener : ExternalSystemTaskNotificationListener {

    override fun onStart(projectPath: String, id: ExternalSystemTaskId) {
        if (!shouldClaimExternalExecute(id.type, remoteFrontendProven())) return
        val project = id.findProject() ?: return
        if (project.isDisposed) return
        val sessions = AffectedRunSessions.getInstance(project)
        if (!sessions.claimExternal()) return
        sessions.register(object : AffectedOwnedSession {
            override fun isActive(): Boolean =
                ExternalSystemProcessingManager.getInstance().findTask(id) != null

            override fun stopIfActive(): Boolean {
                val task = ExternalSystemProcessingManager.getInstance().findTask(id) ?: return false
                return runCatching {
                    task.cancel(ExternalSystemTaskNotificationListener.NULL_OBJECT)
                }.getOrDefault(false)
            }
        })
    }
}

internal fun shouldClaimExternalExecute(type: ExternalSystemTaskType, frontend: Boolean): Boolean =
    !frontend && type == ExternalSystemTaskType.EXECUTE_TASK
