package com.aspix2k.affected.build

import java.io.File

internal fun resolveExecutable(
    name: String,
    path: String? = System.getenv("PATH") ?: System.getenv("Path"),
    pathExt: String? = System.getenv("PATHEXT"),
    separator: String = File.pathSeparator,
): String {
    if (name.isBlank()) return name
    if (name.contains('/') || name.contains('\\')) return runnablePath(File(name)) ?: name
    return firstRunnableOnPath(name, path.orEmpty(), pathExt, separator) ?: name
}

private fun firstRunnableOnPath(name: String, path: String, pathExt: String?, separator: String): String? {
    val suffixes = pathSuffixes(pathExt)
    return path.split(separator)
        .asSequence()
        .map(::File)
        .filter(File::isReadableDirectory)
        .flatMap { folder -> suffixes.asSequence().map { File(folder, name + it) } }
        .firstNotNullOfOrNull(::runnablePath)
}

private fun pathSuffixes(pathExt: String?): List<String> {
    val suffixes = linkedSetOf("")
    pathExt.orEmpty().split(';').map { it.trim() }.filter { it.startsWith('.') }.forEach { suffix ->
        suffixes += suffix
        suffixes += suffix.lowercase()
    }
    return suffixes.toList()
}

private fun runnablePath(file: File): String? =
    file.takeIf { it.isFile && it.canExecute() }?.absoluteFile?.normalize()?.invariantSeparatorsPath

private fun File.isReadableDirectory(): Boolean = isDirectory && canRead()
