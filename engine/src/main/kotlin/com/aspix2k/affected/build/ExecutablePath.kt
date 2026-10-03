package com.aspix2k.affected.build

import java.io.File

internal fun resolveExecutable(
    name: String,
    path: String? = System.getenv("PATH") ?: System.getenv("Path"),
    pathExt: String? = System.getenv("PATHEXT"),
    separator: String = File.pathSeparator,
): String {
    if (name.isBlank()) return name
    if (hasDirectorySeparator(name)) {
        return existingExecutable(File(name)) ?: name
    }
    return firstRunnableOnPath(name, path ?: "", pathExt, separator) ?: name
}

private fun hasDirectorySeparator(name: String): Boolean {
    var index = 0
    while (index < name.length) {
        val char = name[index]
        if (char == '/' || char == '\\') return true
        index += 1
    }
    return false
}

private fun firstRunnableOnPath(name: String, path: String, pathExt: String?, separator: String): String? {
    val suffixes = pathSuffixes(pathExt)
    return path.split(separator)
        .asSequence()
        .map(::File)
        .filter(File::isReadableDirectory)
        .flatMap { folder -> suffixes.asSequence().map { File(folder, name + it) } }
        .firstNotNullOfOrNull(::existingExecutable)
}

private fun pathSuffixes(pathExt: String?): List<String> {
    val suffixes = linkedSetOf("")
    pathExt.orEmpty().split(';').map { it.trim() }.filter { it.startsWith('.') }.forEach { suffix ->
        suffixes += suffix
        suffixes += suffix.lowercase()
    }
    return suffixes.toList()
}

private fun existingExecutable(file: File): String? {
    if (!file.isFile) return null
    if (!file.canExecute()) return null
    return file.absoluteFile.normalize().invariantSeparatorsPath
}

private fun File.isReadableDirectory(): Boolean {
    if (!isDirectory) return false
    return canRead()
}
