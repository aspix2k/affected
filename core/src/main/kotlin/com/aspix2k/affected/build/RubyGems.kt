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
        val lockedRunners = RubyTestSuites.lockedRunners(root)
        if (RubyTestSuites.invalidLock(root, lockedRunners)) return emptyList()

        val described = specs.map { describe(it, lockedRunners) ?: return emptyList() }
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
                testTask = entry.testTask,
                compileTask = null,
                hasTests = entry.testTask != TEST,
                dependencies = dependencies - "$rootPath|${entry.name}",
                executionId = if (entry.directory == rootPath) "." else entry.name,
            )
        }
    }

    fun fallback(root: File, testTask: String): BuildModule {
        val specs = findGemspecs(root)
        val hasSpecs = ManifestSearch.anyFile(root) { it.extension.equals("gemspec", ignoreCase = true) }
        val roots = when {
            hasSpecs == null || hasSpecs && specs.isEmpty() -> listOf(root.invariantSeparatorsPath)
            !hasSpecs -> listOf(root.invariantSeparatorsPath)
            else -> specs.mapNotNull(File::getParentFile).map { it.invariantSeparatorsPath }.distinct()
        }
        return rootFallbackModule(root, testTask, null).copy(contentRoots = roots)
    }

    private data class Described(
        val name: String,
        val directory: String,
        val dependencies: Set<String>,
        val testTask: String,
    )

    private fun describe(
        gemspec: File,
        lockedRunners: Set<RubyTestRunner>?,
    ): Described? {
        val text = ManifestSearch.readText(gemspec) ?: return null
        val directory = gemspec.parentFile ?: return null

        val name = NAME.find(text)?.groupValues?.get(1)
            ?: gemspec.nameWithoutExtension.takeIf { it.isNotEmpty() }
            ?: return null
        val testTask = RubyTestSuites.task(directory, lockedRunners) ?: return null

        return Described(
            name = name,
            directory = directory.invariantSeparatorsPath,
            dependencies = DEPENDENCY.findAll(text).mapTo(HashSet()) { it.groupValues[1] },
            testTask = testTask,
        )
    }

    private fun findGemspecs(root: File): List<File> = ManifestSearch.findByExtension(root, "gemspec")
}
