package com.aspix2k.affected.build

import java.io.File

object RubyGems {

    const val TEST = "test"

    private val NAME = Regex("""\.name\s*=\s*["']([^"']+)["']""")
    private val DEPENDENCY = Regex("""add(?:_runtime|_development)?_dependency\s*[( ]\s*["']([^"']+)["']""")

    fun parse(root: File): List<BuildModule> {
        val rootPath = root.invariantSeparatorsPath
        val specs = findGemspecs(root)
        if (specs.isEmpty()) return emptyList()

        val described = specs.map { describe(it) ?: return emptyList() }
        val names = described.map { it.name }.toSet()
        if (names.size != described.size) return emptyList()

        return described.map { entry ->
            val dependencies = entry.dependencies
                .filter { it in names }
                .mapTo(HashSet()) { "$rootPath|$it" }

            BuildModule(
                id = entry.name,
                root = rootPath,
                contentRoots = listOf(entry.directory),
                testTask = TEST,
                compileTask = null,
                hasTests = entry.hasTests,
                dependencies = dependencies - "$rootPath|${entry.name}",
                executionId = if (entry.directory == rootPath) "." else entry.name,
            )
        }
    }

    private data class Described(
        val name: String,
        val directory: String,
        val dependencies: Set<String>,
        val hasTests: Boolean,
    )

    private fun describe(gemspec: File): Described? {
        val text = ManifestSearch.readText(gemspec) ?: return null
        val directory = gemspec.parentFile ?: return null

        val name = NAME.find(text)?.groupValues?.get(1)
            ?: gemspec.nameWithoutExtension.takeIf { it.isNotEmpty() }
            ?: return null

        return Described(
            name = name,
            directory = directory.invariantSeparatorsPath,
            dependencies = DEPENDENCY.findAll(text).mapTo(HashSet()) { it.groupValues[1] },
            hasTests = TEST_DIRS.any { File(directory, it).isDirectory },
        )
    }

    private fun findGemspecs(root: File): List<File> = ManifestSearch.findByExtension(root, "gemspec")

    private val TEST_DIRS = listOf("spec", "test")
}
