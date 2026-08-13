package com.aspix2k.affected.build

import com.aspix2k.affected.ChangeAnalyzer
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

object BuildSystems {

    private val point = ExtensionPointName<BuildSystem>("com.aspix2k.affected.buildSystem")

    fun of(project: Project): List<BuildSystem> = point.extensionList.filter { it.isPresent(project) }

    fun byId(id: String): BuildSystem? = point.extensionList.firstOrNull { it.id == id }

    fun sourceExtensions(): Set<String> = point.extensionList.flatMapTo(HashSet()) { it.sourceExtensions }

    fun sourceExtensions(project: Project): Set<String> =
        of(project).flatMapTo(HashSet()) { it.sourceExtensions }.ifEmpty { ChangeAnalyzer.DEFAULT_EXTENSIONS }

    fun sourceFileNames(): Set<String> =
        point.extensionList.filterIsInstance<NamedSourceBuildSystem>().flatMapTo(HashSet()) { it.sourceFileNames }

    fun sourceFileNames(project: Project): Set<String> =
        of(project).filterIsInstance<NamedSourceBuildSystem>().flatMapTo(HashSet()) { it.sourceFileNames }

    fun includesAllFileChanges(project: Project): Boolean =
        of(project).any { it is AllFileChangesBuildSystem }
}
