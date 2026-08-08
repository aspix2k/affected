package com.aspix2k.affected

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.util.GradleConstants
import javax.swing.Icon

abstract class RunCheckAction(
    private val taskName: String,
    private val titleKey: String,
    private val actionIcon: Icon,
) : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val state = e.project?.service<AffectedState>()
        val supported = state?.modules.orEmpty().filter { it.supports(taskName) }

        e.presentation.isEnabledAndVisible = supported.isNotEmpty() && state?.isRunning != true
        e.presentation.text = AffectedBundle.message(titleKey)
        e.presentation.icon = actionIcon
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val modules = project.service<AffectedState>().modules.filter { it.supports(taskName) }
        if (modules.isEmpty()) return

        saveAllDocuments()

        modules.groupBy { it.buildRoot }.forEach { (root, group) ->
            run(project, root, group.map { "${it.gradlePath}:$taskName" })
        }
    }

    private fun run(project: Project, root: String, tasks: List<String>) {
        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = root
            taskNames = tasks
            externalSystemIdString = GradleConstants.SYSTEM_ID.id
        }
        ExternalSystemUtil.runTask(
            settings,
            DefaultRunExecutor.EXECUTOR_ID,
            project,
            GradleConstants.SYSTEM_ID,
            null,
            ProgressExecutionMode.IN_BACKGROUND_ASYNC,
        )
    }

    private fun saveAllDocuments() {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            FileDocumentManager.getInstance().saveAllDocuments()
        } else {
            application.invokeAndWait { FileDocumentManager.getInstance().saveAllDocuments() }
        }
    }
}

class RunDetektAction : RunCheckAction("detekt", "action.check.detekt", AffectedIcons.Check)

class RunLintAction : RunCheckAction("lint", "action.check.lint", AffectedIcons.Check)

class RunCoverageAction : RunCheckAction("koverHtmlReport", "action.check.coverage", AffectedIcons.Check)
