package com.aspix2k.affected

import com.aspix2k.affected.build.BuildSystems
import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import java.nio.file.Path

class ChangeListener : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        val extensions = BuildSystems.sourceExtensions()
        val names = BuildSystems.sourceFileNames()
        val frontend = remoteFrontendProven()
        val paths = affectedVfsPaths(events)
        val sourceChanged = shouldRefreshFromVfs(frontend, paths, extensions, names)

        for (project in ProjectManager.getInstance().openProjects) {
            if (!project.isDisposed) {
                val allFileChanged = !sourceChanged && project.basePath?.let { root ->
                    shouldRefreshAllFilesForProject(frontend, root, paths) &&
                        BuildSystems.includesAllFileChanges(project)
                } == true
                if (sourceChanged || allFileChanged) project.service<AffectedState>().invalidate()
            }
        }
    }
}

internal fun affectedVfsPaths(events: List<VFileEvent>): List<String> = events.flatMap { event ->
    when (event) {
        is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
        is VFilePropertyChangeEvent -> if (event.propertyName == VirtualFile.PROP_NAME) {
            listOf(event.oldPath, event.newPath)
        } else {
            listOf(event.path)
        }
        else -> listOf(event.path)
    }
}.distinct()

internal fun remoteFrontendProven(
    platformPrefix: String? = System.getProperty("idea.platform.prefix"),
    rdctClient: String? = System.getProperty("rdct.client"),
): Boolean =
    platformPrefix.equals("JetBrainsClient", ignoreCase = true) ||
        rdctClient.equals("true", ignoreCase = true)

internal fun shouldRefreshOnStartup(frontend: Boolean): Boolean = !frontend

internal fun shouldRefreshFromVfs(
    frontend: Boolean,
    paths: List<String>,
    extensions: Set<String>,
    names: Set<String>,
): Boolean = !frontend && paths.any { isRelevantPath(it, extensions, names) }

internal fun isRelevantPath(path: String, extensions: Set<String>, names: Set<String> = emptySet()): Boolean {
    val normalized = path.replace('\\', '/')
    if (ignoredPath(normalized)) return false
    return normalized.substringAfterLast('.', "").lowercase() in extensions ||
        normalized.substringAfterLast('/') in names
}

internal fun shouldRefreshAllFilesForProject(
    frontend: Boolean,
    projectRoot: String,
    paths: List<String>,
): Boolean = !frontend && runCatching {
    val root = Path.of(projectRoot).toAbsolutePath().normalize()
    paths.any { raw ->
        val candidate = Path.of(raw).toAbsolutePath().normalize()
        if (candidate == root || !candidate.startsWith(root)) return@any false
        val relative = root.relativize(candidate).toString().replace('\\', '/')
        !ignoredPath("/$relative") &&
            !isProjectDocumentation(relative)
    }
}.getOrDefault(false)

private fun ignoredPath(normalized: String): Boolean =
    IGNORED_DIRECTORIES.any(normalized::contains)

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
