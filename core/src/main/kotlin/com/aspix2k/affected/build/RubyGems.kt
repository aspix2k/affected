package com.aspix2k.affected.build

import java.io.File

/**
 * Gems of a Ruby monorepo.
 *
 * A gemspec is Ruby code, so only the two declarations that matter are read:
 * the gem name and the dependencies it adds. Both follow a fixed shape that
 * every gemspec uses, and anything unparseable is simply skipped rather than
 * guessed at.
 *
 * Ruby has nothing to compile, so consumers are never checked — a changed
 * signature surfaces when the consumer's own tests run, which is not something
 * this plugin can shortcut.
 */
object RubyGems {

    const val TEST = "test"

    private val NAME = Regex("""\.name\s*=\s*["']([^"']+)["']""")
    private val DEPENDENCY = Regex("""add(?:_runtime|_development)?_dependency\s*[( ]\s*["']([^"']+)["']""")

    fun parse(root: File): List<BuildModule> {
        val rootPath = root.invariantSeparatorsPath
        val specs = findGemspecs(root)
        if (specs.size < 2) return emptyList()

        val described = specs.mapNotNull { describe(it) }
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
                compileTask = null,
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
    )

    private fun describe(gemspec: File): Described? {
        val text = gemspec.readText()
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

    private fun findGemspecs(root: File): List<File> = root.walkTopDown()
        .onEnter { it.name != ".git" && it.name != "vendor" && it.name != "node_modules" }
        .filter { it.isFile && it.extension == "gemspec" }
        .toList()

    private val TEST_DIRS = listOf("spec", "test")
}
