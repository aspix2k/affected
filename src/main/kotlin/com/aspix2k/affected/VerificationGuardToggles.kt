package com.aspix2k.affected

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction

class RunBeforeCommitToggle : ToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = AffectedSettings.getInstance().runBeforeCommit

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        AffectedSettings.getInstance().runBeforeCommit = state
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.text = AffectedBundle.message("action.before.commit.text")
    }
}

class RunBeforePushToggle : ToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = AffectedSettings.getInstance().runBeforePush

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        AffectedSettings.getInstance().runBeforePush = state
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.text = AffectedBundle.message("action.before.push.text")
    }
}
