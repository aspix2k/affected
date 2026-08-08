package com.aspix2k.affected.build

import com.google.gson.JsonParser
import java.io.File

object NodeWorkspaces {

    const val TEST = "test"

    fun parse(root: File): List<BuildModule> {
        val patterns = patternsOf(root)
        if (patterns.isEmpty()) return emptyList()

        val rootPath = root.invariantSeparatorsPath
        val manifests = patterns.flatMap { expandPattern(root, it) }
            .map { File(it, "package.json") }
            .filter { it.isFile }
            .distinct()

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
                compileTask = "typecheck".takeIf { entry.typed },
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
        val json = runCatching { JsonParser.parseString(manifest.readText()).asJsonObject }.getOrNull() ?: return null
        val name = json.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val directory = manifest.parentFile ?: return null

        val dependencies = listOf("dependencies", "devDependencies", "peerDependencies")
            .mapNotNull { json.getAsJsonObject(it) }
            .flatMapTo(HashSet()) { it.keySet() }

        val scripts = json.getAsJsonObject("scripts")?.keySet().orEmpty()

        return Described(
            name = name,
            directory = directory.invariantSeparatorsPath,
            dependencies = dependencies,
            hasTests = "test" in scripts || hasTestSources(directory),
            typed = File(directory, "tsconfig.json").isFile,
        )
    }

    private fun hasTestSources(directory: File): Boolean =
        TEST_DIRS.any { File(directory, it).isDirectory }

    private fun patternsOf(root: File): List<String> {
        val fromPnpm = File(root, "pnpm-workspace.yaml").takeIf { it.isFile }?.let(::readPnpm).orEmpty()
        if (fromPnpm.isNotEmpty()) return fromPnpm

        val manifest = File(root, "package.json").takeIf { it.isFile } ?: return emptyList()
        val json = runCatching { JsonParser.parseString(manifest.readText()).asJsonObject }.getOrNull()
            ?: return emptyList()

        val workspaces = json.get("workspaces") ?: return emptyList()
        return when {
            workspaces.isJsonArray ->
                workspaces.asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
            workspaces.isJsonObject ->
                workspaces.asJsonObject.getAsJsonArray("packages")
                    ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
                    .orEmpty()
            else -> emptyList()
        }
    }

    private fun readPnpm(file: File): List<String> {
        val patterns = mutableListOf<String>()
        var inPackages = false

        file.readLines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("packages:") -> inPackages = true
                inPackages && trimmed.startsWith("- ") ->
                    patterns += trimmed.removePrefix("- ").trim('\'', '"')
                inPackages && trimmed.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("-") ->
                    inPackages = false
                else -> Unit
            }
        }
        return patterns
    }

    private fun expandPattern(root: File, pattern: String): List<File> {
        val clean = pattern.trim().removePrefix("./").removeSuffix("/")

        return when {
            clean.endsWith("/**") -> {
                val base = File(root, clean.removeSuffix("/**"))
                if (!base.isDirectory) {
                    emptyList()
                } else {
                    base.walkTopDown()
                        .onEnter { it.name != "node_modules" }
                        .filter { it.isDirectory }
                        .toList()
                }
            }
            clean.endsWith("/*") -> {
                val base = File(root, clean.removeSuffix("/*"))
                base.listFiles()?.filter { it.isDirectory && it.name != "node_modules" }.orEmpty()
            }
            else -> listOf(File(root, clean)).filter { it.isDirectory }
        }
    }

    private val TEST_DIRS = listOf("__tests__", "test", "tests", "spec")
}
