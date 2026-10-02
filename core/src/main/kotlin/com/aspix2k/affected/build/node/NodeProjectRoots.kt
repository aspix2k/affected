package com.aspix2k.affected.build.node

import com.aspix2k.affected.build.ManifestSearch
import com.aspix2k.affected.build.isRegularFileNoFollow
import com.aspix2k.affected.build.nestedBuildRoots
import java.io.File
import java.nio.file.Path

internal fun nodeProjectRoots(base: File): List<File> {
    val workspaces = HashMap<File, Set<Path>>()
    return nestedBuildRoots(
        base,
        setOf("package.json"),
        { root, nested ->
            val members = workspaces.getOrPut(root) { nodeWorkspaceDirectories(root) }
            nested.toPath().toAbsolutePath().normalize() in members || !hasNodeProjectFiles(nested)
        },
    ) { File(it, "package.json").isRegularFileNoFollow() }
}

private fun nodeWorkspaceDirectories(root: File): Set<Path> =
    NodeWorkspaces.manifestFiles(root).orEmpty()
        .mapNotNullTo(HashSet()) { it.parentFile?.toPath()?.toAbsolutePath()?.normalize() }

private fun hasNodeProjectFiles(directory: File): Boolean =
    NODE_LOCKFILES.any { File(directory, it).isRegularFileNoFollow() } ||
        ManifestSearch.readText(File(directory, "package.json"))?.let(NODE_SCRIPTS::containsMatchIn) == true

private val NODE_LOCKFILES = listOf("package-lock.json", "yarn.lock", "pnpm-lock.yaml", "bun.lock", "bun.lockb")
private val NODE_SCRIPTS = Regex(""""scripts"\s*:\s*\{""")
