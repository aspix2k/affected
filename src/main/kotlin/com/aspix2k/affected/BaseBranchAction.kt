package com.aspix2k.affected

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages

class BaseBranchAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val branch = AffectedSettings.getInstance().baseBranch
        e.presentation.text = AffectedBundle.message("action.base.branch.text", branch)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val settings = AffectedSettings.getInstance()
        val branch = Messages.showInputDialog(
            e.project,
            AffectedBundle.message("dialog.base.branch.message"),
            AffectedBundle.message("dialog.base.branch.title"),
            null,
            settings.baseBranch,
            BranchNameValidator,
        )?.trim() ?: return
        if (branch == settings.baseBranch) return
        settings.baseBranch = branch
        ProjectManager.getInstance().openProjects
            .filterNot { it.isDisposed }
            .forEach { it.service<AffectedState>().invalidate() }
    }
}

private object BranchNameValidator : InputValidator {
    override fun checkInput(inputString: String): Boolean = !AffectedMcpInputs.validateBaseBranch(inputString).error

    override fun canClose(inputString: String): Boolean = checkInput(inputString)
}
