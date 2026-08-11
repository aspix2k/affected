package com.aspix2k.affected.build

import com.google.gson.JsonObject
import com.google.gson.JsonStreamParser
import java.io.StringReader

object GoPackages {

    const val TEST = "test"
    const val COMPILE = "build"

    fun parse(json: String, root: String): List<BuildModule> {
        val packages = read(json)
        val paths = packages.mapNotNull { it.string("ImportPath") }.toSet()
        if (paths.size != packages.size) return emptyList()

        return packages.map { pkg ->
            val importPath = pkg.string("ImportPath") ?: return emptyList()
            val directory = pkg.string("Dir")?.normalizeSeparators() ?: return emptyList()

            val imports = pkg.strings("Imports") ?: return emptyList()
            val dependencies = imports
                .filter { it in paths }
                .mapTo(HashSet()) { "$root|$it" }

            BuildModule(
                id = importPath,
                root = root,
                contentRoots = listOf(directory),
                testTask = TEST,
                compileTask = COMPILE,
                hasTests = true,
                dependencies = dependencies - "$root|$importPath",
            )
        }
    }

    private fun read(json: String): List<JsonObject> = runCatching {
        val parser = JsonStreamParser(StringReader(json))
        buildList {
            while (parser.hasNext()) {
                val element = parser.next()
                if (element.isJsonObject) add(element.asJsonObject)
            }
        }
    }.getOrElse { emptyList() }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.strings(name: String): List<String>? {
        val value = get(name) ?: return emptyList()
        if (!value.isJsonArray) return null
        return value.asJsonArray.map { it.takeIf { element -> element.isJsonPrimitive }?.asString ?: return null }
    }

    private fun String.normalizeSeparators(): String = replace('\\', '/')
}
