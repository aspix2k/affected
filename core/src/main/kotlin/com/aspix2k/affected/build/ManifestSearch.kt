package com.aspix2k.affected.build

import java.io.File

internal object ManifestSearch {

    private const val MAX_DEPTH = 7

    private val SKIPPED = setOf(
        ".git", ".gradle", ".idea", ".venv", "venv", "node_modules", "vendor",
        "build", "out", "target", "bin", "obj", "dist", "coverage",
        "DerivedData", "Pods", "__pycache__", ".tox", ".cache",
        "cmake-build-debug", "cmake-build-release",
    )

    fun find(root: File, name: String, limit: Int = 4096): List<File> =
        find(root, limit) { it.name == name }

    fun findByExtension(root: File, extension: String, limit: Int = 4096): List<File> =
        find(root, limit) { it.extension.equals(extension, ignoreCase = true) }

    private fun find(root: File, limit: Int, matches: (File) -> Boolean): List<File> {
        val found = ArrayList<File>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue += root to 0

        while (queue.isNotEmpty() && found.size < limit) {
            val (directory, depth) = queue.removeFirst()
            scan(directory, depth, limit, matches, found, queue)
        }
        return found
    }

    private fun scan(
        directory: File,
        depth: Int,
        limit: Int,
        matches: (File) -> Boolean,
        found: MutableList<File>,
        queue: ArrayDeque<Pair<File, Int>>,
    ) {
        val children = directory.listFiles() ?: return

        for (child in children) {
            if (found.size >= limit) return
            when {
                child.isFile && matches(child) -> found += child
                canEnter(child, depth) -> queue += child to depth + 1
            }
        }
    }

    private fun canEnter(file: File, depth: Int): Boolean =
        depth < MAX_DEPTH && file.isDirectory && file.name !in SKIPPED && !file.name.startsWith(".")
}
