package com.aspix2k.affected

import com.aspix2k.affected.build.BuildSystems
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.EnvironmentUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File

object ProjectChanges {

    fun collect(project: Project): ChangeSet {
        val (files, uncovered, analyzer) = changedFiles(project)
        return if (analyzer == null) {
            ChangeSet(
                files,
                files.toSet(),
                emptySet(),
                comparedToBase = false,
                uncovered = uncovered,
                gitUsable = false,
                outsideSources = outsideSources(project, files),
                declaredOwners = declaredOwners(project, files),
            )
        } else {
            ChangeSet(
                files,
                analyzer.apiTouchedAmong(files),
                analyzer.modifiedAgainstBase(),
                comparedToBase = analyzer.hasComparisonBase(),
                baseUnresolved = !analyzer.hasComparisonBase(),
                uncovered = uncovered,
                resolvedBranch = analyzer.resolvedBranch(),
                mergeBase = analyzer.comparisonBase(),
                outsideSources = outsideSources(project, files),
                declaredOwners = declaredOwners(project, files),
            )
        }
    }

    suspend fun collectSuspending(project: Project): ChangeSet =
        runInterruptible(Dispatchers.IO) { collect(project) }

    private fun changedFiles(project: Project): Triple<List<File>, List<File>, ChangeAnalyzer?> {
        val projectDir = project.basePath?.let(::File) ?: return Triple(emptyList(), emptyList(), null)
        val extensions = BuildSystems.sourceExtensions(project)
        val names = BuildSystems.sourceFileNames(project)
        val includeAllFiles = BuildSystems.includesAllFileChanges(project)
        val sourceRoots = if (includeAllFiles) emptySet() else sourceRoots(project, projectDir)
        val declaredPaths = DeclaredDependencies.read(projectDir)?.mapTo(HashSet()) { it.path }
        val accepts = { path: String ->
            declaredPaths == null || path in declaredPaths ||
                isCollectedSource(path, includeAllFiles, extensions, names, sourceRoots)
        }
        val foreign = if (includeAllFiles) emptySet() else BuildSystems.languageExtensions() - extensions
        val uncovers = { path: String ->
            path.substringAfterLast('.', "").lowercase() in foreign && !accepts(path)
        }
        val local = localChanges(project, accepts)
        val localUncovered = localChanges(project, uncovers)
        val analyzer = ChangeAnalyzer(
            projectDir,
            project.service<ProjectBaseBranch>().configured,
            extensions,
            includeAllFiles,
            sourceFileNames = names,
            sourceRoots = sourceRoots,
            excludedRoots = excludedRoots(project, projectDir),
            environment = EnvironmentUtil.getEnvironmentMap(),
            checkCanceled = ProgressManager::checkCanceled,
            declaredPaths = declaredPaths,
        )

        if (!analyzer.isUsable()) return Triple(local, localUncovered, null)

        return Triple(
            (local + analyzer.againstBase()).distinct(),
            (localUncovered + analyzer.againstBase(uncovers)).distinct(),
            analyzer,
        )
    }

    private fun declaredOwners(project: Project, files: List<File>): Map<File, List<DeclaredOwner>>? {
        val projectDir = project.basePath?.let(::File) ?: return emptyMap()
        val declared = DeclaredDependencies.read(projectDir) ?: return null
        return files.associateWith { DeclaredDependencies.owners(projectDir, declared, it) }
    }

    private fun outsideSources(project: Project, files: List<File>): Set<File> {
        val projectDir = project.basePath?.let(::File)
        if (projectDir == null || !BuildSystems.includesAllFileChanges(project)) return emptySet()
        return filesOutsideSources(
            files,
            projectDir,
            BuildSystems.sourceExtensions(project),
            BuildSystems.sourceFileNames(project),
            sourceRoots(project, projectDir),
        )
    }

    internal fun sourceRoots(project: Project, projectDir: File): Set<String> =
        relativeRoots(projectDir) { ProjectRootManager.getInstance(project).contentSourceRoots.asList() }

    internal fun excludedRoots(project: Project, projectDir: File): Set<String> =
        relativeRoots(projectDir) {
            ModuleManager.getInstance(project).modules.flatMap { module ->
                ModuleRootManager.getInstance(module).excludeRoots.asList()
            }
        }

    private fun relativeRoots(projectDir: File, roots: () -> List<VirtualFile>): Set<String> {
        val base = projectDir.invariantSeparatorsPath.trimEnd('/')
        return ApplicationManager.getApplication().runReadAction(
            Computable {
                roots().mapNotNullTo(HashSet()) { root ->
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
