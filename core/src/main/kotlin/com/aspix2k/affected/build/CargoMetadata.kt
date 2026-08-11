package com.aspix2k.affected.build

import com.google.gson.JsonParser

object CargoMetadata {

    private fun String.normalizeSeparators(): String = replace('\\', '/')

    fun parse(json: String, root: String): List<BuildModule> {
        val packages = runCatching {
            JsonParser.parseString(json).asJsonObject.getAsJsonArray("packages")
        }.getOrNull() ?: return emptyList()

        val described = packages.map { element ->
            if (!element.isJsonObject) return emptyList()
            val json = element.asJsonObject
            val name = json.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return emptyList()
            val manifest = json.get("manifest_path")?.takeIf { it.isJsonPrimitive }?.asString
                ?.normalizeSeparators() ?: return emptyList()
            val directory = manifest.substringBeforeLast('/', "").takeIf { it.isNotEmpty() } ?: return emptyList()
            Triple(name, directory, json)
        }
        val names = described.mapTo(HashSet()) { it.first }
        if (names.size != described.size) return emptyList()

        return described.map { (name, directory, json) ->
            val dependencyElements = json.get("dependencies")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return emptyList()
            val dependencies = dependencyElements.map { dependency ->
                dependency.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.get("name")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?: return emptyList()
            }
                .filter { it in names }
                .mapTo(HashSet()) { "$root|$it" }

            BuildModule(
                id = name,
                root = root,
                contentRoots = listOf(directory),
                testTask = TEST,
                compileTask = COMPILE,
                hasTests = true,
                dependencies = dependencies - "$root|$name",
            )
        }
    }

    const val TEST = "test"
    const val COMPILE = "check"
}
