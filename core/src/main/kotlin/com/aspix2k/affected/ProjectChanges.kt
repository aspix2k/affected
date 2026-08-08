package com.aspix2k.affected

import com.aspix2k.affected.build.BuildSystems
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import java.io.File

/**
 * What changed, asking the IDE first.
 *
 * The IDE already tracks the working tree for whichever VCS the project uses,
 * so local edits cost no processes and work outside git. Comparing against a
 * base branch is git-specific and stays with [ChangeAnalyzer]; a project under
 * another VCS simply gets its local changes.
 */
object ProjectChanges {

    data class Result(val files: List<File>, val apiTouched: Set<File>, val comparedToBase: Boolean)

    fun collect(project: Project): Result {
        val projectDir = project.basePath?.let(::File) ?: return Result(emptyList(), emptySet(), false)
        val extensions = BuildSystems.sourceExtensions(project)

        val local = localChanges(project, extensions)
        val analyzer = ChangeAnalyzer(projectDir, AffectedSettings.getInstance().baseBranch, extensions)

        if (!analyzer.isUsable()) {
            // Without git there is nothing to diff against, so every changed file
            // is treated as capable of touching API and consumers are checked.
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
