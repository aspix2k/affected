package com.aspix2k.affected

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction

class TestDependentsToggle : ToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = AffectedSettings.getInstance().testDependents

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        AffectedSettings.getInstance().testDependents = state
        invalidateOpenProjects()
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.text = AffectedBundle.message("action.dependents.text")
        e.presentation.description = AffectedBundle.message(
            if (isSelected(e)) "action.dependents.description.on" else "action.dependents.description.off"
        )
    }
}
