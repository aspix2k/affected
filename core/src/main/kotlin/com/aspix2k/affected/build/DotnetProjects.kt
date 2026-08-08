package com.aspix2k.affected.build

import java.io.File

/**
 * .NET projects and the references between them.
 *
 * A project file lists its dependencies as `ProjectReference` paths, written
 * with Windows separators regardless of the host, and relative to the project
 * that declares them.
 */
object DotnetProjects {

    const val TEST = "test"
    const val COMPILE = "build"

    private val PROJECT_EXTENSIONS = setOf("csproj", "fsproj", "vbproj")
    private val REFERENCE = Regex("""<ProjectReference\s+Include\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val TEST_MARKERS = listOf(
        "Microsoft.NET.Test.Sdk",
        "xunit",
        "NUnit",
        "MSTest.TestFramework",
        "Microsoft.Testing.Platform",
    )

    fun parse(root: File): List<BuildModule> {
        val rootPath = root.invariantSeparatorsPath
        val projects = findProjects(root)
        if (projects.isEmpty()) return emptyList()

        val byPath = projects.associateBy { it.invariantSeparatorsPath }

        return projects.map { project ->
            val text = project.readText()
            val directory = project.parentFile

            val dependencies = REFERENCE.findAll(text)
                .mapNotNull { match -> resolve(directory, match.groupValues[1]) }
                .mapNotNull { byPath[it] }
                .mapTo(HashSet()) { "$rootPath|${it.nameWithoutExtension}" }

            BuildModule(
                id = project.nameWithoutExtension,
                root = rootPath,
                contentRoots = listOf(directory.invariantSeparatorsPath),
                testTask = TEST,
                compileTask = COMPILE,
                hasTests = TEST_MARKERS.any { text.contains(it, ignoreCase = true) },
                dependencies = dependencies - "$rootPath|${project.nameWithoutExtension}",
            )
        }
    }

    internal fun isProjectFile(file: File): Boolean =
        file.isFile && file.extension.lowercase() in PROJECT_EXTENSIONS

    // A reference reads as ..\..\src\Lib\Lib.csproj and has to become a path we can look up.
    // normalize resolves the parent segments without touching the disk, so it does not
    // follow symlinks and the result still matches the paths the walk produced.
    private fun resolve(from: File, reference: String): String? {
        val normalised = reference.replace('\\', '/')
        return runCatching { File(from, normalised).normalize().invariantSeparatorsPath }.getOrNull()
    }

    private fun findProjects(root: File): List<File> =
        PROJECT_EXTENSIONS.flatMap { ManifestSearch.findByExtension(root, it) }
}
