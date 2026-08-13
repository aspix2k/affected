package com.aspix2k.affected.build

import com.google.gson.JsonParser

object CargoMetadata {

    private fun String.normalizeSeparators(): String = replace('\\', '/')

    fun parse(json: String, root: String, testTask: (Boolean) -> String = { TEST }): List<BuildModule> {
        val normalizedRoot = root.normalizeSeparators()
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
                .mapTo(HashSet()) { "$normalizedRoot|$it" }
            val hasDoctests = packageHasDoctests(json) ?: return emptyList()

            BuildModule(
                id = name,
                root = normalizedRoot,
                contentRoots = listOf(directory),
                testTask = testTask(hasDoctests),
                compileTask = COMPILE,
                hasTests = true,
                dependencies = dependencies - "$normalizedRoot|$name",
            )
        }
    }

    fun hasCustomBuild(json: String): Boolean? = runCatching {
        val packages = JsonParser.parseString(json).asJsonObject.getAsJsonArray("packages") ?: return null
        packages.any { packageElement ->
            val targets = packageElement.asJsonObject.getAsJsonArray("targets") ?: return null
            targets.any { targetElement ->
                val kinds = targetElement.asJsonObject.getAsJsonArray("kind") ?: return null
                if (kinds.any { !it.isJsonPrimitive }) return null
                kinds.any { it.asString == "custom-build" }
            }
        }
    }.getOrNull()

    private fun packageHasDoctests(json: com.google.gson.JsonObject): Boolean? {
        val targets = json.get("targets")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return targets.any { target ->
            if (!target.isJsonObject) return null
            val targetObject = target.asJsonObject
            val kinds = targetObject.get("kind")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            val kindNames = kinds.map { kind ->
                kind.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
            }
            val doctest = targetObject.get("doctest")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                ?.asBoolean ?: return null
            doctest && kindNames.any(LIBRARY_TARGET_KINDS::contains)
        }
    }

    const val TEST = "test"
    const val COMPILE = "check"

    private val LIBRARY_TARGET_KINDS = setOf("lib", "proc-macro")
}
