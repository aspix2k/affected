package com.aspix2k.affected

import com.aspix2k.affected.build.BuildSystems
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vcs.changes.ChangeListManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File

object ProjectChanges {

    data class Result(
        val files: List<File>,
        val apiTouched: Set<File>,
        val exactSelectionEligible: Set<File>,
        val comparedToBase: Boolean,
        val baseUnresolved: Boolean = false,
        val uncovered: List<File> = emptyList(),
    )

    fun collect(project: Project): Result {
        val (files, uncovered, analyzer) = changedFiles(project)
        return if (analyzer == null) {
            Result(files, files.toSet(), emptySet(), comparedToBase = false, uncovered = uncovered)
        } else {
            Result(
                files,
                analyzer.apiTouchedAmong(files),
                analyzer.modifiedAgainstBase(),
                comparedToBase = analyzer.hasComparisonBase(),
                baseUnresolved = !analyzer.hasComparisonBase(),
                uncovered = uncovered,
            )
        }
    }

    suspend fun collectSuspending(project: Project): Result =
        runInterruptible(Dispatchers.IO) { collect(project) }

    private fun changedFiles(project: Project): Triple<List<File>, List<File>, ChangeAnalyzer?> {
        val projectDir = project.basePath?.let(::File) ?: return Triple(emptyList(), emptyList(), null)
        val extensions = BuildSystems.sourceExtensions(project)
        val names = BuildSystems.sourceFileNames(project)
        val includeAllFiles = BuildSystems.includesAllFileChanges(project)
        val sourceRoots = if (includeAllFiles) emptySet() else sourceRoots(project, projectDir)
        val accepts = { path: String -> isCollectedSource(path, includeAllFiles, extensions, names, sourceRoots) }
        val foreign = if (includeAllFiles) emptySet() else BuildSystems.languageExtensions() - extensions
        val uncovers = { path: String ->
            path.substringAfterLast('.', "").lowercase() in foreign && !accepts(path)
        }
        val local = localChanges(project, accepts)
        val localUncovered = localChanges(project, uncovers)
        val analyzer = ChangeAnalyzer(
            projectDir,
            AffectedSettings.getInstance().baseBranch,
            extensions,
            includeAllFiles,
            sourceFileNames = names,
            sourceRoots = sourceRoots,
        )

        if (!analyzer.isUsable()) return Triple(local, localUncovered, null)

        return Triple(
            (local + analyzer.againstBase()).distinct(),
            (localUncovered + analyzer.againstBase(uncovers)).distinct(),
            analyzer,
        )
    }

    internal fun sourceRoots(project: Project, projectDir: File): Set<String> {
        val base = projectDir.invariantSeparatorsPath.trimEnd('/')
        return ApplicationManager.getApplication().runReadAction(
            Computable {
                ProjectRootManager.getInstance(project).contentSourceRoots.mapNotNullTo(HashSet()) { root ->
                    root.path.takeIf { it.startsWith("$base/") }?.removePrefix("$base/")
                }
            },
        )
    }

    private fun localChanges(project: Project, accepts: (String) -> Boolean): List<File> {
        val projectDir = project.basePath?.let(::File) ?: return emptyList()
        val manager = ChangeListManager.getInstance(project)

        val tracked = manager.affectedPaths + manager.modifiedWithoutEditing.map { File(it.path) }
        val untracked = manager.unversionedFilesPaths.map { it.ioFile }.filter { it.isFile }

        return (tracked + untracked)
            .filter { file ->
                val relative = runCatching { file.relativeTo(projectDir).invariantSeparatorsPath }
                    .getOrDefault(file.invariantSeparatorsPath)
                accepts(relative)
            }
            .distinct()
    }
}
