package com.aspix2k.affected.build

import com.google.gson.JsonObject
import com.google.gson.JsonStreamParser
import java.io.StringReader

object GoPackages {

    const val TEST = "test"
    const val COMPILE = "build"

    /**
     * `go list -json` writes a stream of objects rather than an array, so the
     * parser reads them one by one. A package is a module here: Go tests and
     * builds packages, not the module the go.mod file declares.
     */
    fun parse(json: String, root: String): List<BuildModule> {
        val packages = read(json)
        val paths = packages.mapNotNull { it.string("ImportPath") }.toSet()

        return packages.mapNotNull { pkg ->
            val importPath = pkg.string("ImportPath") ?: return@mapNotNull null
            val directory = pkg.string("Dir")?.normalizeSeparators() ?: return@mapNotNull null

            val dependencies = pkg.strings("Imports")
                .filter { it in paths }
                .mapTo(HashSet()) { "$root|$it" }

            BuildModule(
                id = importPath,
                root = root,
                contentRoots = listOf(directory),
                testTask = TEST,
                compileTask = COMPILE,
                hasTests = pkg.strings("TestGoFiles").isNotEmpty() || pkg.strings("XTestGoFiles").isNotEmpty(),
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

    private fun JsonObject.strings(name: String): List<String> =
        getAsJsonArray(name)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()

    private fun String.normalizeSeparators(): String = replace('\\', '/')
}
