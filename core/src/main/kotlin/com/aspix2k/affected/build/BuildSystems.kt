package com.aspix2k.affected.build

import com.aspix2k.affected.ChangeAnalyzer
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

object BuildSystems {

    private val point = ExtensionPointName<BuildSystem>("com.aspix2k.affected.buildSystem")

    fun of(project: Project): List<BuildSystem> = point.extensionList.filter { it.isPresent(project) }

    fun byId(id: String): BuildSystem? = point.extensionList.firstOrNull { it.id == id }

    fun sourceExtensions(): Set<String> = point.extensionList.flatMapTo(HashSet()) { it.sourceExtensions }

    fun languageExtensions(): Set<String> = languageExtensions(sourceExtensions())

    fun sourceExtensions(project: Project): Set<String> =
        of(project).flatMapTo(HashSet()) { it.sourceExtensions }.ifEmpty { ChangeAnalyzer.DEFAULT_EXTENSIONS }

    fun sourceFileNames(): Set<String> =
        point.extensionList.mapNotNull { it.capability<NamedSourceBuildSystem>() }
            .flatMapTo(HashSet()) { it.sourceFileNames }

    fun sourceFileNames(project: Project): Set<String> =
        of(project).mapNotNull { it.capability<NamedSourceBuildSystem>() }.flatMapTo(HashSet()) { it.sourceFileNames }

    fun includesAllFileChanges(project: Project, systems: List<BuildSystem> = point.extensionList): Boolean =
        presentAllFileSystems(project, systems).isNotEmpty()

    fun generatedFileChangeRoots(project: Project, systems: List<BuildSystem> = point.extensionList): List<String> =
        presentAllFileSystems(project, systems)
            .filter { it.capability<AllFileChangesBuildSystem>()?.includeGeneratedFiles == true }
            .flatMap { system -> system.modules(project).map(BuildModule::root) }

    private fun presentAllFileSystems(project: Project, systems: List<BuildSystem>): List<BuildSystem> =
        systems.filter { it.capability<AllFileChangesBuildSystem>() != null && it.isPresent(project) }
}

internal fun languageExtensions(extensions: Set<String>): Set<String> = extensions - DATA_FORMATS

private val DATA_FORMATS = setOf(
    "json", "jsonc", "xml", "yml", "yaml", "toml", "properties", "lock", "txt", "cfg", "ini",
    "mod", "sum", "work", "plist", "pro", "hcl", "sql", "xcscheme", "xctestplan",
)
