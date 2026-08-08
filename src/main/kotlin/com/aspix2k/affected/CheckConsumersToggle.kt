package com.aspix2k.affected

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction

class CheckConsumersToggle : ToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = AffectedSettings.getInstance().checkConsumers

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        AffectedSettings.getInstance().checkConsumers = state
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.text = AffectedBundle.message("action.consumers.text")
        e.presentation.description = AffectedBundle.message(
            if (isSelected(e)) "action.consumers.description.on" else "action.consumers.description.off"
        )
    }
}
