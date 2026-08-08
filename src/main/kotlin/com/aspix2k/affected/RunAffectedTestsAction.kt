package com.aspix2k.affected

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.currentThreadCoroutineScope
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RunAffectedTestsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val state = project?.service<AffectedState>()

        if (state == null) {
            e.presentation.isEnabled = false
            return
        }

        when {
            state.isRunning -> {
                e.presentation.isEnabled = false
                e.presentation.text = AffectedBundle.message("action.run.text")
                e.presentation.description = AffectedBundle.message("action.run.description.running")
            }
            !state.ready -> {
                e.presentation.isEnabled = false
                e.presentation.text = AffectedBundle.message("action.run.text")
                e.presentation.description = AffectedBundle.message("action.run.description.counting")
            }
            state.affectedModules == 0 -> {
                e.presentation.isEnabled = false
                e.presentation.text = AffectedBundle.message("action.run.text")
                e.presentation.description = AffectedBundle.message("action.run.description.nothing")
            }
            else -> {
                e.presentation.isEnabled = true
                e.presentation.text = AffectedBundle.message("action.run.text.count", state.affectedModules)
                e.presentation.description = AffectedBundle.message("action.run.description")
            }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        FileDocumentManager.getInstance().saveAllDocuments()
        currentThreadCoroutineScope().launch {
            val changes = withContext(Dispatchers.IO) { ProjectChanges.collect(project) }
            if (changes.files.isEmpty()) {
                notify(
                    project,
                    AffectedBundle.message("notification.nothing.title"),
                    AffectedBundle.message("notification.nothing.text"),
                    NotificationType.INFORMATION,
                )
                return@launch
            }

            val plan = Verification.plan(project, changes)
            if (plan.isEmpty) {
                notify(
                    project,
                    AffectedBundle.message("notification.unresolved.title"),
                    AffectedBundle.message("notification.unresolved.text", changes.files.size),
                    NotificationType.WARNING,
                )
                return@launch
            }

            execute(project, plan)
        }
    }

    private suspend fun execute(project: Project, plan: Plan) {
        notify(
            project,
            AffectedBundle.message("notification.started.title"),
            describe(plan),
            NotificationType.INFORMATION,
        )
        Verification.runAndWait(project, plan)
    }

    private fun describe(plan: Plan): String =
        if (plan.compiled == 0) {
            AffectedBundle.message("plan.tests", plan.tested)
        } else {
            AffectedBundle.message("plan.tests.consumers", plan.tested, plan.compiled)
        }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AffectedTests")
            .createNotification(title, content, type)
            .notify(project)
    }
}
