package com.aspix2k.affected.build

import java.io.File

internal fun resolveExecutable(
    name: String,
    path: String? = System.getenv("PATH") ?: System.getenv("Path"),
    pathExt: String? = System.getenv("PATHEXT"),
    separator: String = File.pathSeparator,
): String {
    if (name.isBlank()) return name
    if (name.contains('/') || name.contains('\\')) {
        val file = File(name)
        return if (isRunnable(file)) file.canonicalFile.invariantSeparatorsPath else name
    }
    val suffixes = linkedSetOf("")
    pathExt.orEmpty().split(';').map { it.trim() }.filter { it.startsWith('.') }.forEach { suffixes += it }
    for (directory in path.orEmpty().split(separator)) {
        if (directory.isBlank()) continue
        val folder = File(directory)
        if (!folder.isDirectory || !folder.canRead()) continue
        for (suffix in suffixes) {
            val candidate = File(folder, name + suffix)
            if (isRunnable(candidate)) return candidate.canonicalFile.invariantSeparatorsPath
        }
    }
    return name
}

private fun isRunnable(file: File): Boolean = file.isFile && file.canExecute()
