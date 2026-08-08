package com.aspix2k.affected

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService

class AffectedGroup : DefaultActionGroup(), DumbAware {

    init {
        isPopup = true
        templatePresentation.putClientProperty(ActionUtil.HIDE_DROPDOWN_ICON, true)
        templatePresentation.icon = AffectedIcons.Action
        templatePresentation.text = AffectedBundle.message("group.title")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val state = project?.service<AffectedState>()
        val ready = project != null && state?.ready == true && !DumbService.isDumb(project)
        val animate = AffectedSettings.getInstance().animateWhileRunning

        e.presentation.isEnabled = ready
        e.presentation.icon = AffectedIcons.forState(
            if (ready) state.verificationStatus else VerificationStatus.RUNNING,
            state?.affectedModules ?: 0,
            animate,
        )
        e.presentation.disabledIcon = if (!ready && animate) AffectedIcons.DisabledRunning else null
        e.presentation.text = when {
            state == null || !state.ready -> AffectedBundle.message("group.title")
            state.isRunning -> AffectedBundle.message("group.title.running")
            state.affectedModules == 0 -> AffectedBundle.message("group.title")
            else -> AffectedBundle.message("group.title.count", state.affectedModules)
        }
    }
}
