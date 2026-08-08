package com.aspix2k.affected

import com.aspix2k.affected.build.BuildSystems
import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

class ChangeListener : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        val extensions = BuildSystems.sourceExtensions()
        if (events.none { isRelevantPath(it.path, extensions) }) return

        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            project.service<AffectedState>().invalidate()
        }
    }
}

internal fun isRelevantPath(path: String, extensions: Set<String>): Boolean {
    if (IGNORED_DIRECTORIES.any(path::contains)) return false
    return path.substringAfterLast('.', "").lowercase() in extensions
}

private val IGNORED_DIRECTORIES = listOf(
    "/.git/",
    "/.gradle/",
    "/.idea/",
    "/.venv/",
    "/.cache/",
    "/.tox/",
    "/build/",
    "/cmake-build-",
    "/coverage/",
    "/DerivedData/",
    "/dist/",
    "/node_modules/",
    "/obj/",
    "/out/",
    "/Pods/",
    "/target/",
    "/vendor/",
    "/venv/",
    "/__pycache__/",
)
