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
        val project = e.project
        val branch = project?.service<ProjectBaseBranch>()?.configured
            ?: project?.service<AffectedState>()?.snapshot()?.changes?.resolvedBranch
                ?.let { AffectedBundle.message("base.branch.auto.resolved", it) }
            ?: AffectedBundle.message("base.branch.auto")
        e.presentation.text = AffectedBundle.message("action.base.branch.text", branch)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val setting = project.service<ProjectBaseBranch>()
        val branch = Messages.showInputDialog(
            project,
            AffectedBundle.message("dialog.base.branch.message"),
            AffectedBundle.message("dialog.base.branch.title"),
            null,
            setting.configured.orEmpty(),
            BranchNameValidator,
        ) ?: return
        if (setting.configure(branch)) project.service<AffectedState>().invalidate()
    }
}

internal fun invalidateOpenProjects() {
    ProjectManager.getInstance().openProjects
        .filterNot { it.isDisposed }
        .forEach { it.service<AffectedState>().invalidate() }
}

private object BranchNameValidator : InputValidator {
    override fun checkInput(inputString: String): Boolean = !AffectedMcpInputs.validateBaseBranch(inputString).error

    override fun canClose(inputString: String): Boolean = checkInput(inputString)
}
