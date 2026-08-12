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
        val project = e.project
        val state = project?.service<AffectedState>()
        val animate = AffectedSettings.getInstance().animateWhileRunning
        val snapshot = state?.snapshot()
        val uiState = snapshot?.let { affectedUiState(it, ideBusy = projectBusy(project)) }

        e.presentation.isEnabled = project != null
        e.presentation.icon = when {
            uiState?.animated == true && animate -> AffectedIcons.Running
            uiState == AffectedUiState.READY -> AffectedIcons.withCount(snapshot.affectedModules)
            else -> AffectedIcons.Action
        }
        e.presentation.disabledIcon = null
        e.presentation.text = AffectedBundle.message(uiState?.groupTitleKey ?: "group.title")
        e.presentation.description = when (uiState) {
            AffectedUiState.ANALYZING -> AffectedBundle.message("action.run.description.counting")
            AffectedUiState.BUSY -> AffectedBundle.message("action.run.description.busy")
            AffectedUiState.PREPARING -> AffectedBundle.message("action.run.description.preparing")
            AffectedUiState.UNAVAILABLE -> AffectedBundle.message("notification.unresolved.title")
            else -> null
        }
    }
}
