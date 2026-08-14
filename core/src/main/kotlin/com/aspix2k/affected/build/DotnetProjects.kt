package com.aspix2k.affected.build

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

    fun parse(root: File): List<BuildModule> {
        val rootPath = root.invariantSeparatorsPath
        val projects = findProjects(root)
        if (projects.isEmpty()) return emptyList()

        val byPath = projects.associateBy { it.invariantSeparatorsPath }
        val nameCounts = projects.groupingBy { it.nameWithoutExtension }.eachCount()
        val ids = projects.associateWith { project ->
            if (nameCounts.getValue(project.nameWithoutExtension) == 1) {
                project.nameWithoutExtension
            } else {
                relativeProjectPath(rootPath, project).substringBeforeLast('.')
            }
        }

        return projects.map { project ->
            val text = ManifestSearch.readText(project) ?: return emptyList()
            val directory = project.parentFile

            val dependencies = REFERENCE.findAll(text)
                .mapNotNull { match -> INCLUDE.find(match.groupValues[1])?.groupValues?.get(1) }
                .mapNotNull { reference -> resolve(directory, reference) }
                .mapNotNull { byPath[it] }
                .mapTo(HashSet()) { moduleDependencyKey("DOTNET", rootPath, ids.getValue(it)) }

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

    internal fun isProjectFile(file: File): Boolean =
        file.isRegularFileNoFollow() && file.extension.lowercase() in PROJECT_EXTENSIONS

    private fun resolve(from: File, reference: String): String? {
        val normalised = reference.replace('\\', '/')
        return runCatching { File(from, normalised).normalize().invariantSeparatorsPath }.getOrNull()
    }

    private fun findProjects(root: File): List<File> =
        PROJECT_EXTENSIONS.flatMap { ManifestSearch.findByExtension(root, it) }

    private fun relativeProjectPath(root: String, project: File): String =
        project.invariantSeparatorsPath.removePrefix("$root/")
}
