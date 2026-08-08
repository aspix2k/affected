package com.aspix2k.affected

import com.aspix2k.affected.build.BuildSystems
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import java.io.File

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
        val projectDir = project.basePath?.let(::File) ?: return

        saveAllDocuments()

        val title = AffectedBundle.message("progress.title")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val settings = AffectedSettings.getInstance()
                val changes = ChangeAnalyzer(projectDir, settings.baseBranch, BuildSystems.sourceExtensions(project)).collect()
                if (changes.files.isEmpty()) {
                    notify(
                        project,
                        AffectedBundle.message("notification.nothing.title"),
                        AffectedBundle.message("notification.nothing.text"),
                        NotificationType.INFORMATION,
                    )
                    return
                }

                val plan = ApplicationManager.getApplication().runReadAction<Plan> {
                    buildPlan(project, changes)
                }

                if (plan.isEmpty) {
                    notify(
                        project,
                        AffectedBundle.message("notification.unresolved.title"),
                        AffectedBundle.message("notification.unresolved.text", changes.files.size),
                        NotificationType.WARNING,
                    )
                    return
                }

                ApplicationManager.getApplication().invokeLater { execute(project, plan) }
            }
        })
    }

    private fun buildPlan(project: Project, changes: ChangeAnalyzer.Changes): Plan {
        val graph = ModuleGraph(project)

        val changed = changes.files.mapNotNull { graph.nodeFor(it) }.distinct()
        val apiNodes = changes.apiTouched.mapNotNull { graph.nodeFor(it) }.toSet()
        val consumers = when {
            !AffectedSettings.getInstance().checkConsumers -> emptyList()
            apiNodes.isEmpty() -> emptyList()
            else -> graph.directDependents(apiNodes)
        }

        return TaskPlanner.plan(changed.map { it.info() }, consumers.map { it.info() })
    }

    private fun execute(project: Project, plan: Plan) {
        project.service<AffectedState>().markRunning(true)
        plan.groups.forEach { group ->
            BuildSystems.byId(group.systemId)?.run(project, group.root, group.tasks)
        }
        notify(
            project,
            AffectedBundle.message("notification.started.title"),
            describe(plan),
            NotificationType.INFORMATION,
        )
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

    private fun saveAllDocuments() {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            FileDocumentManager.getInstance().saveAllDocuments()
        } else {
            application.invokeAndWait { FileDocumentManager.getInstance().saveAllDocuments() }
        }
    }
}
