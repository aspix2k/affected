package com.aspix2k.affected.build

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

internal object SwiftTargets {

    val SAFE_NAME = Regex("[A-Za-z0-9_]+")

    fun parse(json: String, root: File): List<BuildModule>? {
        val targets = runCatching {
            JsonParser.parseString(json).asJsonObject.getAsJsonArray("targets").map { it.asJsonObject }
        }.getOrNull() ?: return null
        val names = targets.map { it.string("name") ?: return null }
        if (names.isEmpty() || names.toSet().size != names.size) return null
        val rootPath = root.invariantSeparatorsPath
        val code = targets.filter { (it.string("type") ?: return null) in SUPPORTED_TYPES }
        val codeNames = code.mapTo(HashSet()) { it.string("name").orEmpty() }
        if (codeNames.isEmpty()) return null
        return code.map { target ->
            val name = target.string("name") ?: return null
            val type = target.string("type") ?: return null
            val path = target.string("path") ?: return null
            if (target.string("c99name") != name || !SAFE_NAME.matches(name)) return null
            val dependencies = target.strings("target_dependencies") ?: return null
            BuildModule(
                id = name,
                root = rootPath,
                contentRoots = listOf(contentRoot(root, path)),
                testTask = SwiftTasks.TEST,
                compileTask = SwiftTasks.BUILD,
                hasTests = type == TEST_TYPE,
                dependencies = dependencies.filter { it in codeNames && it != name }.mapTo(HashSet()) { dependency ->
                    "$rootPath|$dependency"
                },
            )
        }
    }

    private fun contentRoot(root: File, path: String): String =
        File(path).let { if (it.isAbsolute) it else File(root, path) }.normalize().invariantSeparatorsPath

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.strings(name: String): List<String>? {
        val value = get(name) ?: return emptyList()
        if (!value.isJsonArray) return null
        return value.asJsonArray.map { it.takeIf { element -> element.isJsonPrimitive }?.asString ?: return null }
    }

    private const val TEST_TYPE = "test"
    private val SUPPORTED_TYPES = setOf("library", "executable", "macro", TEST_TYPE)
}
