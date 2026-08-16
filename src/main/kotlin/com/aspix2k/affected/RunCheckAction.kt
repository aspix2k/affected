package com.aspix2k.affected

import com.aspix2k.affected.build.BuildSystems
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.currentThreadCoroutineScope
import com.intellij.openapi.project.DumbAware
import kotlinx.coroutines.Dispatchers

abstract class RunCheckAction(
    private val taskName: String,
    private val titleKey: String,
    private val actionIcon: javax.swing.Icon,
) : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val state = project?.service<AffectedState>()
        val snapshot = state?.snapshot()
        val supported = snapshot?.modules.orEmpty().filter { it.supports(taskName) }
        val uiState = snapshot?.let { affectedUiState(it, ideBusy = projectBusy(project)) }

        e.presentation.isVisible = supported.isNotEmpty()
        e.presentation.isEnabled = supported.isNotEmpty() && uiState == AffectedUiState.READY
        e.presentation.text = AffectedBundle.message(titleKey)
        e.presentation.icon = actionIcon
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val state = project.service<AffectedState>()
        saveAllDocuments()
        if (projectBusy(project)) return
        val claim = state.tryClaimReadyRun() ?: return
        launchClaimed(claim, ::currentThreadCoroutineScope) {
            val modules = claim.snapshot.modules.filter { it.supports(taskName) }
            if (modules.isEmpty()) return@launchClaimed
            val groups = TaskPlanner.groups(modules.map(AffectedModule::info), taskName)
            if (projectBusy(project)) return@launchClaimed
            if (!claim.markRunning()) return@launchClaimed
            val stopAfterFirstFailure = AffectedSettings.getInstance().stopAfterFirstFailure
            runClaimedGroups(claim, groups, Dispatchers.IO, stopAfterFirstFailure) { group ->
                group.runInPlannedExecutionRoot(project) {
                    runWithRequiredAdapter(BuildSystems.byId(group.systemId)) {
                        it.runAndWait(project, group.root, group.tasks)
                    }
                }
            }
        }
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
