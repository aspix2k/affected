package com.aspix2k.affected

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

class ChangeListener : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        if (events.none { it.isRelevant() }) return

        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            project.service<AffectedState>().invalidate()
        }
    }

    private fun VFileEvent.isRelevant(): Boolean {
        if (IGNORED_DIRS.any { path.contains(it) }) return false
        return RELEVANT_SUFFIXES.any { path.endsWith(it) }
    }

    private companion object {
        val RELEVANT_SUFFIXES = listOf(".kt", ".kts", ".java", ".xml", ".json", ".pro")
        val IGNORED_DIRS = listOf("/build/", "/.gradle/", "/.git/")
    }
}
