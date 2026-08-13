package com.aspix2k.affected.build

import com.google.gson.JsonParser
import java.io.File

internal fun bunManager(root: File): String? {
    if (!bunDeclared(root)) return null
    if (otherNodeManagerDeclared(root)) return null
    return "bun"
}

internal fun bunDeclared(root: File): Boolean {
    if (File(root, "bun.lock").isFile || File(root, "bun.lockb").isFile) return true
    return packageManagerName(root) == "bun"
}

private fun otherNodeManagerDeclared(root: File): Boolean =
    File(root, "yarn.lock").isFile ||
        File(root, "pnpm-lock.yaml").isFile ||
        File(root, "pnpm-workspace.yaml").isFile ||
        File(root, "package-lock.json").isFile ||
        File(root, "npm-shrinkwrap.json").isFile

private fun packageManagerName(root: File): String? = runCatching {
    val text = ManifestSearch.readText(File(root, "package.json")) ?: return null
    val value = JsonParser.parseString(text).asJsonObject.get("packageManager") ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
    value.asString.substringBefore('@').takeIf { it.isNotEmpty() }
}.getOrNull()
