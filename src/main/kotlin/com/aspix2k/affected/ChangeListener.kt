package com.aspix2k.affected

import com.aspix2k.affected.build.BuildSystems
import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

class ChangeListener : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        val extensions = BuildSystems.sourceExtensions()
        val names = BuildSystems.sourceFileNames()
        if (!shouldRefreshFromVfs(remoteFrontendProven(), events.map { it.path }, extensions, names)) return

        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            project.service<AffectedState>().invalidate()
        }
    }
}

internal fun remoteFrontendProven(
    platformPrefix: String? = System.getProperty("idea.platform.prefix"),
    rdctClient: String? = System.getProperty("rdct.client"),
): Boolean =
    platformPrefix.equals("JetBrainsClient", ignoreCase = true) ||
        rdctClient.equals("true", ignoreCase = true)

internal fun shouldRefreshFromVfs(
    frontend: Boolean,
    paths: List<String>,
    extensions: Set<String>,
    names: Set<String>,
): Boolean = !frontend && paths.any { isRelevantPath(it, extensions, names) }

internal fun isRelevantPath(path: String, extensions: Set<String>, names: Set<String> = emptySet()): Boolean {
    val normalized = path.replace('\\', '/')
    if (IGNORED_DIRECTORIES.any(normalized::contains)) return false
    return normalized.substringAfterLast('.', "").lowercase() in extensions ||
        normalized.substringAfterLast('/') in names
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
