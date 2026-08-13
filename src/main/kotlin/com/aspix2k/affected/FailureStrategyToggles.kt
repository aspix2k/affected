package com.aspix2k.affected

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction

class RunFullPlanToggle : ToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = !AffectedSettings.getInstance().stopAfterFirstFailure

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (state) AffectedSettings.getInstance().stopAfterFirstFailure = false
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.text = AffectedBundle.message("action.failure.full.text")
        e.presentation.description = AffectedBundle.message("action.failure.full.description")
    }
}

class StopAfterFirstFailureToggle : ToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = AffectedSettings.getInstance().stopAfterFirstFailure

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (state) AffectedSettings.getInstance().stopAfterFirstFailure = true
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.text = AffectedBundle.message("action.failure.stop.text")
        e.presentation.description = AffectedBundle.message("action.failure.stop.description")
    }
}
