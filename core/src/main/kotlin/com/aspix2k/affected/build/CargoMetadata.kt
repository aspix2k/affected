package com.aspix2k.affected.build

import com.google.gson.JsonParser
import java.io.File

object CargoMetadata {

    /** cargo reports OS-native paths; the graph is keyed on normalised ones. */
    private fun String.normalizeSeparators(): String = replace('\\', '/')

    fun parse(json: String, root: String): List<BuildModule> {
        val packages = runCatching {
            JsonParser.parseString(json).asJsonObject.getAsJsonArray("packages")
        }.getOrNull() ?: return emptyList()

        val names = packages.mapNotNull { it.asJsonObject.get("name")?.asString }.toSet()

        return packages.mapNotNull { element ->
            val json = element.asJsonObject
            val name = json.get("name")?.asString ?: return@mapNotNull null
            val manifest = json.get("manifest_path")?.asString?.normalizeSeparators() ?: return@mapNotNull null
            val directory = manifest.substringBeforeLast('/', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null

            val dependencies = json.getAsJsonArray("dependencies")
                ?.mapNotNull { it.asJsonObject.get("name")?.asString }
                ?.filter { it in names }
                ?.mapTo(HashSet()) { "$root|$it" }
                .orEmpty()

            BuildModule(
                id = name,
                root = root,
                contentRoots = listOf(directory),
                testTask = TEST,
                compileTask = COMPILE,
                hasTests = hasTests(File(directory)),
                dependencies = dependencies - "$root|$name",
            )
        }
    }

    private fun hasTests(directory: File): Boolean {
        if (File(directory, "tests").isDirectory) return true
        val sources = File(directory, "src")
        if (!sources.isDirectory) return false
        return sources.walkTopDown()
            .filter { it.isFile && it.extension == "rs" }
            .any { file ->
                file.useLines { lines ->
                    lines.any { it.contains("#[test]") || it.contains("#[cfg(test)]") }
                }
            }
    }

    const val TEST = "test"
    const val COMPILE = "check"
}
