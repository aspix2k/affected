package com.aspix2k.affected

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction

class AnimateWhileRunningToggle : ToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = AffectedSettings.getInstance().animateWhileRunning

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        AffectedSettings.getInstance().animateWhileRunning = state
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.text = AffectedBundle.message("action.animation.text")
    }
}
