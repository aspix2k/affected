package com.aspix2k.affected

import java.io.File
import java.lang.ProcessBuilder.Redirect

internal data class Changes(val files: List<File>, val apiTouched: Set<File>)

internal fun ChangeAnalyzer.collectPaths(): List<File> {
    if (!isUsable()) return emptyList()
    val local = gitPaths(projectDir, "diff", "--name-only", "--no-renames", "-z", "HEAD") +
        gitPaths(projectDir, "ls-files", "--others", "--exclude-standard", "-z")
    return (againstBase() + keepSources(local)).distinct()
}

internal fun ChangeAnalyzer.collect(): Changes {
    val files = collectPaths()
    return Changes(files, if (files.isEmpty()) emptySet() else apiTouchedAmong(files))
}

private fun gitPaths(directory: File, vararg args: String): List<String> {
    val process = ProcessBuilder(listOf("git", "-c", "core.quotePath=false") + args)
        .directory(directory)
        .redirectError(Redirect.DISCARD)
        .start()
    val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
    return if (process.waitFor() == 0) output.split('\u0000').filter(String::isNotEmpty) else emptyList()
}
