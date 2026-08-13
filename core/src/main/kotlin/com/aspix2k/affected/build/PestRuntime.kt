package com.aspix2k.affected.build

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Path

internal fun pestDeclared(root: Path): Boolean = pestDeclared(root.toFile())

internal fun pestDeclared(root: File): Boolean {
    if (lockDeclaresPest(File(root, "composer.lock"))) return true
    return ManifestSearch.find(root, "composer.json").any(::manifestDeclaresPest)
}

private fun manifestDeclaresPest(manifest: File): Boolean = runCatching {
    val json = parseObject(manifest) ?: return false
    listOf("require", "require-dev").any { section ->
        json.get(section)?.takeIf { it.isJsonObject }?.asJsonObject?.keySet().orEmpty().any(::isPestPackage)
    }
}.getOrDefault(false)

private fun lockDeclaresPest(lock: File): Boolean = runCatching {
    val json = parseObject(lock) ?: return false
    listOf("packages", "packages-dev").any { section ->
        val packages = json.get(section)?.takeIf { it.isJsonArray }?.asJsonArray ?: return@any false
        packages.any { element -> element.asObjectName()?.let(::isPestPackage) == true }
    }
}.getOrDefault(false)

private fun parseObject(file: File) = runCatching {
    val text = ManifestSearch.readText(file) ?: return@runCatching null
    JsonParser.parseString(text).asJsonObject
}.getOrNull()

private fun JsonElement.asObjectName(): String? =
    takeIf { it.isJsonObject }?.asJsonObject?.get("name")?.takeIf { it.isJsonPrimitive }?.asString

internal fun isPestPackage(name: String): Boolean =
    name == "pestphp/pest" || name.startsWith("pestphp/pest-")
