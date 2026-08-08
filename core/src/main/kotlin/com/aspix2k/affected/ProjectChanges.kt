package com.aspix2k.affected

import com.aspix2k.affected.build.BuildSystems
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import java.io.File

object ProjectChanges {

    data class Result(val files: List<File>, val apiTouched: Set<File>, val comparedToBase: Boolean)

    fun collect(project: Project): Result {
        val projectDir = project.basePath?.let(::File) ?: return Result(emptyList(), emptySet(), false)
        val extensions = BuildSystems.sourceExtensions(project)

        val local = localChanges(project, extensions)
        val analyzer = ChangeAnalyzer(projectDir, AffectedSettings.getInstance().baseBranch, extensions)

        if (!analyzer.isUsable()) {
            return Result(local, local.toSet(), comparedToBase = false)
        }

        val files = (local + analyzer.againstBase()).distinct()
        return Result(files, analyzer.apiTouchedAmong(files), comparedToBase = true)
    }

    fun paths(project: Project): List<File> = collect(project).files

    private fun localChanges(project: Project, extensions: Set<String>): List<File> {
        val manager = ChangeListManager.getInstance(project)

        val tracked = (manager.affectedFiles + manager.modifiedWithoutEditing).map { File(it.path) }
        val untracked = manager.unversionedFilesPaths.map { it.ioFile }

        return (tracked + untracked)
            .filter { it.isFile && it.extension in extensions }
            .distinct()
    }
}
