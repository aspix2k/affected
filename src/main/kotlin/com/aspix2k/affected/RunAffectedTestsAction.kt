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

class RunAffectedTestsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val state = project?.service<AffectedState>()

        if (project == null || state == null) {
            e.presentation.isEnabled = false
            return
        }

        val snapshot = state.snapshot()
        val uiState = affectedUiState(snapshot, ideBusy = projectBusy(project))
        e.presentation.text = AffectedBundle.message(uiState.runActionTextKey)
        e.presentation.isEnabled = uiState.canRun

        when (uiState) {
            AffectedUiState.RUNNING -> {
                e.presentation.description = AffectedBundle.message("action.run.description.running")
            }
            AffectedUiState.PREPARING -> {
                e.presentation.description = AffectedBundle.message("action.run.description.preparing")
            }
            AffectedUiState.BUSY -> {
                e.presentation.description = AffectedBundle.message("action.run.description.busy")
            }
            AffectedUiState.ANALYZING -> {
                e.presentation.description = AffectedBundle.message("action.run.description.counting")
            }
            AffectedUiState.UNAVAILABLE -> {
                e.presentation.description = AffectedBundle.message("notification.unresolved.title")
            }
            AffectedUiState.EMPTY -> {
                e.presentation.description = AffectedBundle.message("action.run.description.nothing")
            }
            AffectedUiState.READY -> {
                e.presentation.description = AffectedBundle.message("action.run.description")
            }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val state = project.service<AffectedState>()
        FileDocumentManager.getInstance().saveAllDocuments()
        if (projectBusy(project)) return
        val claim = state.tryClaimReadyRun() ?: return

        launchClaimed(claim, ::currentThreadCoroutineScope) {
            val changes = requireNotNull(claim.changes)
            val prepared = requireNotNull(claim.prepared)
            if (changes.files.isEmpty()) {
                notify(
                    project,
                    AffectedBundle.message("notification.nothing.title"),
                    AffectedBundle.message("notification.nothing.text"),
                    NotificationType.INFORMATION,
                )
                return@launchClaimed
            }

            val plan = prepared.plan
            if (plan.isEmpty) {
                notify(
                    project,
                    AffectedBundle.message("notification.unresolved.title"),
                    AffectedBundle.message("notification.unresolved.text", changes.files.size),
                    NotificationType.WARNING,
                )
                return@launchClaimed
            }
            if (projectBusy(project)) return@launchClaimed

            Verification.runClaimedAndWait(project, prepared, claim)
        }
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AffectedTests")
            .createNotification(title, content, type)
            .notify(project)
    }
}
