package com.aspix2k.affected.build

import java.io.File

object PythonProjects {

    const val TEST = "test"
    const val TYPECHECK = "typecheck"

    fun parse(root: File): List<BuildModule> {
        val rootPath = root.invariantSeparatorsPath
        val manifests = findManifests(root)
        if (manifests.size < 2) return emptyList()

        val described = manifests.mapNotNull { describe(it) }
        val names = described.map { it.name }.toSet()

        return described.map { entry ->
            val dependencies = entry.dependencies
                .filter { it in names }
                .mapTo(HashSet()) { "$rootPath|$it" }

            BuildModule(
                id = entry.name,
                root = rootPath,
                contentRoots = listOf(entry.directory),
                testTask = TEST,
                compileTask = TYPECHECK.takeIf { entry.typed },
                hasTests = entry.hasTests,
                dependencies = dependencies - "$rootPath|${entry.name}",
            )
        }
    }

    private data class Described(
        val name: String,
        val directory: String,
        val dependencies: Set<String>,
        val hasTests: Boolean,
        val typed: Boolean,
    )

    private fun describe(manifest: File): Described? {
        val directory = manifest.parentFile ?: return null
        val lines = manifest.readLines()

        val name = valueOf(lines, "name") ?: return null

        return Described(
            name = name,
            directory = directory.invariantSeparatorsPath,
            dependencies = dependenciesOf(lines),
            hasTests = hasTests(directory),
            typed = lines.any { it.trim().startsWith("[tool.mypy") } || File(directory, "mypy.ini").isFile,
        )
    }

    private fun valueOf(lines: List<String>, key: String): String? = lines
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key ") && it.contains('=') }
        ?.substringAfter('=')
        ?.trim()
        ?.trim('"', '\'')
        ?.takeIf { it.isNotEmpty() }

    private fun dependenciesOf(lines: List<String>): Set<String> {
        val result = HashSet<String>()
        var inList = false

        lines.forEach { raw ->
            val line = raw.trim()
            when {
                DEPENDENCY_KEYS.any { line.startsWith(it) } -> {
                    inList = true
                    result += namesIn(line.substringAfter('='))
                }
                inList && line.startsWith('[') && line.endsWith(']') && !line.contains('"') -> inList = false
                inList -> result += namesIn(line)
            }
            if (inList && line.endsWith("]") && !line.startsWith("[")) inList = false
        }
        return result
    }

    private fun namesIn(fragment: String): List<String> = fragment
        .split(',')
        .map { it.trim().trim('[', ']').trim('"', '\'') }
        .filter { it.isNotEmpty() }
        .map { entry -> entry.takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' } }
        .filter { it.isNotEmpty() }

    private fun hasTests(directory: File): Boolean {
        if (TEST_DIRS.any { File(directory, it).isDirectory }) return true
        return directory.walkTopDown()
            .onEnter { it.name != ".venv" && it.name != "node_modules" }
            .any { it.isFile && it.name.startsWith("test_") && it.extension == "py" }
    }

    private fun findManifests(root: File): List<File> = ManifestSearch.find(root, "pyproject.toml")

    private val DEPENDENCY_KEYS = listOf("dependencies =", "dependencies=", "install_requires =")
    private val TEST_DIRS = listOf("tests", "test")
}
