package com.aspix2k.affected

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
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
        presentAffectedGroup(
            presentation = e.presentation,
            snapshot = project?.service<AffectedState>()?.snapshot(),
            ideBusy = project != null && projectBusy(project),
            animateWhileRunning = AffectedSettings.getInstance().animateWhileRunning,
            projectAvailable = project != null,
        )
    }
}

internal fun presentAffectedGroup(
    presentation: Presentation,
    snapshot: AffectedStateSnapshot?,
    ideBusy: Boolean,
    animateWhileRunning: Boolean,
    projectAvailable: Boolean,
) {
    val uiState = snapshot?.let { affectedUiState(it, ideBusy) }
    presentation.isEnabled = projectAvailable
    presentation.icon = when {
        uiState?.animated == true && animateWhileRunning -> AffectedIcons.Running
        uiState == AffectedUiState.READY -> AffectedIcons.withCount(snapshot.affectedModules)
        else -> AffectedIcons.Action
    }
    presentation.disabledIcon = null
    presentation.text = AffectedBundle.message(uiState?.groupTitleKey ?: "group.title")
    presentation.description = when (uiState) {
        AffectedUiState.ANALYZING -> AffectedBundle.message("action.run.description.counting")
        AffectedUiState.BUSY -> AffectedBundle.message("action.run.description.busy")
        AffectedUiState.PREPARING -> AffectedBundle.message("action.run.description.preparing")
        AffectedUiState.UNAVAILABLE -> AffectedBundle.message("notification.unresolved.title")
        else -> null
    }
}
