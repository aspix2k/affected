package com.aspix2k.affected

import com.aspix2k.affected.build.BuildSystems
import com.intellij.openapi.project.Project
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
    )

    fun collect(project: Project): Result {
        val (files, analyzer) = changedFiles(project)
        return if (analyzer == null) {
            Result(files, files.toSet(), emptySet(), comparedToBase = false)
        } else {
            Result(
                files,
                analyzer.apiTouchedAmong(files),
                analyzer.modifiedAgainstBase(),
                comparedToBase = analyzer.hasComparisonBase(),
            )
        }
    }

    suspend fun collectSuspending(project: Project): Result =
        runInterruptible(Dispatchers.IO) { collect(project) }

    private fun changedFiles(project: Project): Pair<List<File>, ChangeAnalyzer?> {
        val projectDir = project.basePath?.let(::File) ?: return emptyList<File>() to null
        val extensions = BuildSystems.sourceExtensions(project)
        val names = BuildSystems.sourceFileNames(project)
        val includeAllFiles = BuildSystems.includesAllFileChanges(project)
        val local = localChanges(project, extensions, names, includeAllFiles)
        val analyzer = ChangeAnalyzer(
            projectDir,
            AffectedSettings.getInstance().baseBranch,
            extensions,
            names,
            includeAllFiles,
        )

        if (!analyzer.isUsable()) return local to null

        return (local + analyzer.againstBase()).distinct() to analyzer
    }

    private fun localChanges(
        project: Project,
        extensions: Set<String>,
        names: Set<String>,
        includeAllFiles: Boolean,
    ): List<File> {
        val manager = ChangeListManager.getInstance(project)

        val tracked = manager.affectedPaths + manager.modifiedWithoutEditing.map { File(it.path) }
        val untracked = manager.unversionedFilesPaths.map { it.ioFile }.filter { it.isFile }

        return (tracked + untracked)
            .filter { includeAllFiles || it.extension.lowercase() in extensions || it.name in names }
            .distinct()
    }
}
