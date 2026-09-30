package com.aspix2k.affected.build.python

import com.aspix2k.affected.build.ManifestSearch
import com.aspix2k.affected.build.isRegularFileNoFollow
import com.aspix2k.affected.build.process.CliCommand
import com.aspix2k.affected.build.resolveExecutable
import java.io.File

internal data class PythonToolchain(val runner: PythonTestRunner, val launcher: List<String>)

internal fun pythonToolchain(root: File): PythonToolchain =
    PythonToolchain(pythonTestRunner(root), pythonLauncher(root))

internal fun pythonLauncher(
    root: File,
    path: String? = System.getenv("PATH") ?: System.getenv("Path"),
    pathExt: String? = System.getenv("PATHEXT"),
): List<String> {
    val uvLock = File(root, "uv.lock").isRegularFileNoFollow()
    val poetryLock = File(root, "poetry.lock").isRegularFileNoFollow()
    if (uvLock == poetryLock) return emptyList()
    val pyproject = ManifestSearch.readText(File(root, "pyproject.toml")).orEmpty()
    val launcher = if (uvLock) {
        if (POETRY_TABLE.containsMatchIn(pyproject)) return emptyList()
        UV_LAUNCHER
    } else {
        if (UV_TABLE.containsMatchIn(pyproject)) return emptyList()
        POETRY_LAUNCHER
    }
    val tool = launcher.first()
    return launcher.takeIf { resolveExecutable(tool, path, pathExt) != tool }.orEmpty()
}

internal fun CliCommand.inPythonLauncher(launcher: List<String>): CliCommand {
    if (launcher.isEmpty() || arguments.firstOrNull() != "python" || arguments.getOrNull(1) == "-c") return this
    return copy(arguments = launcher + arguments)
}

private val UV_LAUNCHER = listOf("uv", "run", "--locked")
private val POETRY_LAUNCHER = listOf("poetry", "run")
private val UV_TABLE = Regex("""(?m)^\s*\[tool\.uv[.\]]""")
private val POETRY_TABLE = Regex("""(?m)^\s*\[tool\.poetry[.\]]""")
