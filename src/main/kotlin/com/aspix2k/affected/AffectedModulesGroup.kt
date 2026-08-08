package com.aspix2k.affected

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.io.File

class AffectedModulesGroup : DefaultActionGroup(), DumbAware {

    init {
        isPopup = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val modules = e.project?.service<AffectedState>()?.modules.orEmpty()
        e.presentation.isEnabledAndVisible = modules.isNotEmpty()
        e.presentation.text = AffectedBundle.message("group.modules", modules.size)
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return EMPTY_ARRAY
        return project.service<AffectedState>().modules
            .sortedBy { it.id }
            .map { OpenModuleAction(it) }
            .toTypedArray()
    }

    private class OpenModuleAction(private val module: AffectedModule) : AnAction(), DumbAware {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.text = module.id
            e.presentation.description = module.directory
            e.presentation.icon = AffectedIcons.Module
        }

        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            open(project)
        }

        private fun open(project: Project) {
            val target = module.testDirectory?.let(::File)
                ?: BUILD_SCRIPTS.map { File(module.directory, it) }.firstOrNull { it.isFile }
                ?: File(module.directory)

            val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target) ?: return
            if (file.isDirectory) {
                selectInProjectView(project, file)
            } else {
                OpenFileDescriptor(project, file).navigate(true)
            }
        }

        private fun selectInProjectView(project: Project, file: VirtualFile) {
            val directory = PsiManager.getInstance(project).findDirectory(file) ?: return
            ProjectView.getInstance(project).select(directory, file, true)
        }

        private companion object {
            val BUILD_SCRIPTS = listOf("build.gradle.kts", "build.gradle")
        }
    }
}
