package com.aspix2k.affected.build

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

object ComposerPackages {

    const val TEST = "test"
    const val ANALYSE = "analyse"

    fun parse(root: File): List<BuildModule> {
        val rootPath = root.invariantSeparatorsPath
        val manifests = findManifests(root)
        if (manifests.isEmpty()) return emptyList()

        val described = manifests.map { describe(it) ?: return emptyList() }
        val names = described.map { it.name }.toSet()
        if (names.size != described.size) return emptyList()

        return described.map { entry ->
            val dependencies = entry.requires
                .filter { it in names }
                .mapTo(HashSet()) { "$rootPath|$it" }
            val runnable = entry.hasTests || entry.analysed

            BuildModule(
                id = entry.name,
                root = rootPath,
                contentRoots = listOf(entry.directory),
                testTask = if (entry.hasTests) TEST else ANALYSE,
                compileTask = ANALYSE.takeIf { entry.analysed },
                hasTests = runnable,
                dependencies = dependencies - "$rootPath|${entry.name}",
                executionId = if (entry.directory == rootPath) "." else entry.name,
            )
        }
    }

    private data class Described(
        val name: String,
        val directory: String,
        val requires: Set<String>,
        val hasTests: Boolean,
        val analysed: Boolean,
    )

    private fun describe(manifest: File): Described? {
        val text = ManifestSearch.readText(manifest) ?: return null
        val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        val name = json.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val directory = manifest.parentFile ?: return null

        val requires = listOf("require", "require-dev")
            .mapNotNull { json.getAsJsonObject(it) }
            .flatMapTo(HashSet()) { it.keySet() }

        return Described(
            name = name,
            directory = directory.invariantSeparatorsPath,
            requires = requires,
            hasTests = hasTests(directory, json),
            analysed = isAnalysed(directory, json),
        )
    }

    private fun hasTests(directory: File, json: JsonObject): Boolean {
        if (TEST_DIRS.any { File(directory, it).isDirectory }) return true
        if (CONFIGS.any { File(directory, it).isFile }) return true
        return json.getAsJsonObject("scripts")?.keySet().orEmpty().any { it.startsWith("test") }
    }

    private fun isAnalysed(directory: File, json: JsonObject): Boolean {
        if (ANALYSIS_CONFIGS.any { File(directory, it).isFile }) return true
        val requires = listOf("require", "require-dev")
            .mapNotNull { json.getAsJsonObject(it) }
            .flatMap { it.keySet() }
        return requires.any { it.contains("phpstan") || it.contains("psalm") }
    }

    private fun findManifests(root: File): List<File> = ManifestSearch.find(root, "composer.json")

    private val TEST_DIRS = listOf("tests", "test", "Tests")
    private val CONFIGS = listOf("phpunit.xml", "phpunit.xml.dist", "phpunit.dist.xml")
    private val ANALYSIS_CONFIGS = listOf(
        "phpstan.neon",
        "phpstan.neon.dist",
        "phpstan.dist.neon",
        "psalm.xml",
        "psalm.xml.dist",
    )
}
