package com.aspix2k.affected.build

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

object ComposerPackages {

    const val TEST = "test"
    const val PEST = "test-pest"
    const val ANALYSE = "analyse"

    fun parse(root: File): List<BuildModule> {
        val rootPath = root.invariantSeparatorsPath
        val manifests = findManifests(root)
        if (manifests.isEmpty()) return emptyList()

        val described = manifests.map { describe(it) ?: return emptyList() }
        val names = described.map { it.name }.toSet()
        if (names.size != described.size) return emptyList()
        val pestTask = pestTask(root, rootPath, described) ?: return emptyList()
        if (pestTask.task != null && described.any(Described::customTestContract)) return emptyList()
        val pestSuites = if (pestTask.task != null) {
            ComposerPest.suiteDirectories(root, described.map { File(it.directory) }) ?: return emptyList()
        } else {
            emptyMap()
        }

        return described.map { entry ->
            buildModule(rootPath, names, pestTask, pestSuites, entry) ?: return emptyList()
        }
    }

    private fun buildModule(
        rootPath: String,
        names: Set<String>,
        pestTask: PestTask,
        pestSuites: Map<String, List<File>>,
        entry: Described,
    ): BuildModule? {
        val dependencies = entry.requires
            .filter { it in names }
            .mapTo(HashSet()) { "$rootPath|$it" }
        val packageHasTests = if (pestTask.task != null) {
            pestSuites[File(entry.directory).toPath().toAbsolutePath().normalize().toString()]?.isNotEmpty()
                ?: return null
        } else {
            entry.hasTests
        }
        return BuildModule(
            id = entry.name,
            root = rootPath,
            contentRoots = listOf(entry.directory),
            testTask = when {
                !packageHasTests -> ANALYSE
                pestTask.task != null -> pestTask.task
                else -> TEST
            },
            compileTask = ANALYSE.takeIf { entry.analysed },
            hasTests = packageHasTests || entry.analysed,
            dependencies = dependencies - "$rootPath|${entry.name}",
            executionId = if (entry.directory == rootPath) "." else entry.name,
        )
    }

    private data class Described(
        val name: String,
        val directory: String,
        val requires: Set<String>,
        val hasTests: Boolean,
        val analysed: Boolean,
        val pestConstraint: String?,
        val customTestContract: Boolean,
    )

    private data class PestTask(val task: String?)

    private fun pestTask(root: File, rootPath: String, described: List<Described>): PestTask? {
        val pestEntries = described.filter { it.pestConstraint != null }
        if (pestEntries.isEmpty()) return PestTask(null)
        val rootEntry = described.singleOrNull { it.directory == rootPath } ?: return null
        if (pestEntries.size != 1 || pestEntries.single().directory != rootPath) return null
        return ComposerPest.task(root, requireNotNull(rootEntry.pestConstraint))?.let(::PestTask)
    }

    private fun describe(manifest: File): Described? {
        val text = ManifestSearch.readText(manifest) ?: return null
        val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        val name = json.get("name")?.takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isString
        }?.asString ?: return null
        val directory = manifest.parentFile ?: return null

        val dependencies = dependencyVersions(json) ?: return null
        val customTestContract = customTestContract(directory, json) ?: return null

        return Described(
            name = name,
            directory = directory.invariantSeparatorsPath,
            requires = dependencies.keys,
            hasTests = TEST_DIRS.any { File(directory, it).isDirectory } || customTestContract,
            analysed = isAnalysed(directory, json),
            pestConstraint = dependencies[PEST_PACKAGE],
            customTestContract = customTestContract,
        )
    }

    fun fallbackTask(root: File): String {
        val manifests = findManifests(root)
        if (manifests.isEmpty()) return INVALID
        val declarations = manifests.map { manifest ->
            val text = ManifestSearch.readText(manifest) ?: return INVALID
            val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return INVALID
            customTestContract(manifest.parentFile ?: return INVALID, json) ?: return INVALID
            dependencyVersions(json)?.containsKey(PEST_PACKAGE) ?: return INVALID
        }
        return if (declarations.any { it }) INVALID else TEST
    }

    private fun customTestContract(directory: File, json: JsonObject): Boolean? {
        if (CONFIGS.any { File(directory, it).isFile }) return true
        val scripts = json.get("scripts") ?: return false
        if (!scripts.isJsonObject) return null
        return scripts.asJsonObject.keySet().any { it.startsWith("test") }
    }

    private fun isAnalysed(directory: File, json: JsonObject): Boolean {
        if (ANALYSIS_CONFIGS.any { File(directory, it).isFile }) return true
        val requires = dependencyVersions(json)?.keys ?: return false
        return requires.any { it.contains("phpstan") || it.contains("psalm") }
    }

    private fun dependencyVersions(json: JsonObject): Map<String, String>? {
        val dependencies = LinkedHashMap<String, String>()
        for (key in listOf("require", "require-dev")) {
            val section = json.get(key) ?: continue
            if (!section.isJsonObject) return null
            val values = stringValues(section.asJsonObject) ?: return null
            if (dependencies.keys.any(values::containsKey)) return null
            dependencies.putAll(values)
        }
        return dependencies
    }

    private fun stringValues(json: JsonObject): Map<String, String>? {
        val values = LinkedHashMap<String, String>()
        for ((name, value) in json.entrySet()) {
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
            values[name] = value.asString
        }
        return values
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
    private const val PEST_PACKAGE = "pestphp/pest"

    const val INVALID = "invalid"
}
