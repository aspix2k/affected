package com.aspix2k.affected

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

class AffectedGroup : DefaultActionGroup(), DumbAware {

    init {
        isPopup = true
        templatePresentation.putClientProperty(ActionUtil.HIDE_DROPDOWN_ICON, true)
        templatePresentation.icon = AffectedIcons.Action
        templatePresentation.text = AffectedBundle.message("group.title")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val state = e.project?.service<AffectedState>()

        e.presentation.icon = when {
            state?.isRunning == true -> AffectedIcons.Running
            else -> AffectedIcons.withCount(state?.affectedModules ?: 0)
        }
        e.presentation.disabledIcon = e.presentation.icon
        e.presentation.text = when {
            state == null || !state.ready -> AffectedBundle.message("group.title")
            state.isRunning -> AffectedBundle.message("group.title.running")
            state.affectedModules == 0 -> AffectedBundle.message("group.title")
            else -> AffectedBundle.message("group.title.count", state.affectedModules)
        }
    }
}
