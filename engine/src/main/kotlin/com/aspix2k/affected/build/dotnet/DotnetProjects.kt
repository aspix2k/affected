package com.aspix2k.affected.build.dotnet

import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.ManifestSearch
import com.aspix2k.affected.build.isRegularFileNoFollow
import com.aspix2k.affected.build.moduleDependencyKey
import java.io.File

object DotnetProjects {

    const val TEST = "test"
    const val COMPILE = "build"

    private val PROJECT_EXTENSIONS = setOf("csproj", "fsproj", "vbproj")
    private val REFERENCE = Regex("""<ProjectReference\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val INCLUDE = Regex("""\bInclude\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val TEST_MARKERS = listOf(
        "Microsoft.NET.Test.Sdk",
        "xunit",
        "NUnit",
        "MSTest.TestFramework",
        "MSTest.Sdk",
        "Microsoft.Testing.Platform",
    )

    fun parse(root: File, siblings: List<File> = emptyList()): List<BuildModule> {
        val rootPath = root.invariantSeparatorsPath
        val projects = findProjects(root)
        if (projects.isEmpty()) return emptyList()

        val byPath = projects.associateBy { it.invariantSeparatorsPath }
        val ids = projectIds(rootPath, projects)
        val siblingKeys by lazy { siblingDependencyKeys(siblings) }

        return projects.map { project ->
            val text = ManifestSearch.readText(project) ?: return emptyList()
            val directory = project.parentFile

            val dependencies = REFERENCE.findAll(text)
                .mapNotNull { match -> INCLUDE.find(match.groupValues[1])?.groupValues?.get(1) }
                .mapNotNull { reference -> resolve(directory, reference) }
                .mapNotNullTo(HashSet()) { path ->
                    byPath[path]?.let { moduleDependencyKey("DOTNET", rootPath, ids.getValue(it)) } ?: siblingKeys[path]
                }

            val id = ids.getValue(project)
            val hasTests = TEST_MARKERS.any { text.contains(it, ignoreCase = true) }

            BuildModule(
                id = id,
                root = rootPath,
                contentRoots = listOf(directory.invariantSeparatorsPath),
                testTask = if (hasTests) TEST else COMPILE,
                compileTask = COMPILE,
                hasTests = true,
                dependencies = dependencies - moduleDependencyKey("DOTNET", rootPath, id),
                executionId = relativeProjectPath(rootPath, project),
                systemId = "DOTNET",
            )
        }
    }

    private fun projectIds(rootPath: String, projects: List<File>): Map<File, String> {
        val nameCounts = projects.groupingBy { it.nameWithoutExtension }.eachCount()
        return projects.associateWith { project ->
            if (nameCounts.getValue(project.nameWithoutExtension) == 1) {
                project.nameWithoutExtension
            } else {
                relativeProjectPath(rootPath, project).substringBeforeLast('.')
            }
        }
    }

    private fun siblingDependencyKeys(siblings: List<File>): Map<String, String> =
        siblings.flatMap { sibling ->
            val siblingPath = sibling.invariantSeparatorsPath
            projectIds(siblingPath, findProjects(sibling)).map { (project, id) ->
                project.invariantSeparatorsPath to moduleDependencyKey("DOTNET", siblingPath, id)
            }
        }.toMap()

    internal fun isProjectFile(file: File): Boolean =
        file.isRegularFileNoFollow() && file.extension.lowercase() in PROJECT_EXTENSIONS

    private fun resolve(from: File, reference: String): String? {
        val normalised = reference.replace('\\', '/')
        return runCatching { File(from, normalised).normalize().invariantSeparatorsPath }.getOrNull()
    }

    private fun findProjects(root: File): List<File> =
        ManifestSearch.find(root, emptySet(), PROJECT_EXTENSIONS)
            .sortedBy { PROJECT_EXTENSIONS.indexOf(it.extension.lowercase()) }

    private fun relativeProjectPath(root: String, project: File): String =
        project.invariantSeparatorsPath.removePrefix("$root/")
}
