package com.aspix2k.affected

import com.aspix2k.affected.build.BuildSystems
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File

object ProjectChanges {

    data class Result(val files: List<File>, val apiTouched: Set<File>, val comparedToBase: Boolean)

    fun collect(project: Project): Result {
        val (files, analyzer) = changedFiles(project)
        return if (analyzer == null) {
            Result(files, files.toSet(), comparedToBase = false)
        } else {
            Result(files, analyzer.apiTouchedAmong(files), comparedToBase = true)
        }
    }

    suspend fun collectSuspending(project: Project): Result =
        runInterruptible(Dispatchers.IO) { collect(project) }

    fun paths(project: Project): List<File> = changedFiles(project).first

    suspend fun pathsSuspending(project: Project): List<File> =
        runInterruptible(Dispatchers.IO) { paths(project) }

    private fun changedFiles(project: Project): Pair<List<File>, ChangeAnalyzer?> {
        val projectDir = project.basePath?.let(::File) ?: return emptyList<File>() to null
        val extensions = BuildSystems.sourceExtensions(project)
        val names = BuildSystems.sourceFileNames(project)
        val local = localChanges(project, extensions, names)
        val analyzer = ChangeAnalyzer(projectDir, AffectedSettings.getInstance().baseBranch, extensions, names)

        if (!analyzer.isUsable()) return local to null

        return (local + analyzer.againstBase()).distinct() to analyzer
    }

    private fun localChanges(project: Project, extensions: Set<String>, names: Set<String>): List<File> {
        val manager = ChangeListManager.getInstance(project)

        val tracked = manager.affectedPaths + manager.modifiedWithoutEditing.map { File(it.path) }
        val untracked = manager.unversionedFilesPaths.map { it.ioFile }.filter { it.isFile }

        return (tracked + untracked)
            .filter { it.extension.lowercase() in extensions || it.name in names }
            .distinct()
    }
}
