package com.aspix2k.affected.build.python

import com.aspix2k.affected.build.ManifestSearch
import com.aspix2k.affected.build.isRegularFileNoFollow
import com.aspix2k.affected.build.process.CliCommand
import com.aspix2k.affected.build.resolveExecutable
import java.io.File
import java.nio.file.FileSystems

internal data class PythonLauncher(val arguments: List<String> = emptyList(), val failure: String? = null)

internal data class PythonToolchain(val runner: PythonTestRunner, val launcher: PythonLauncher = PythonLauncher())

internal fun pythonToolchain(root: File): PythonToolchain =
    PythonToolchain(pythonTestRunner(root), pythonLauncher(root))

internal fun pythonLauncher(
    root: File,
    path: String? = System.getenv("PATH") ?: System.getenv("Path"),
    pathExt: String? = System.getenv("PATHEXT"),
    toolDirectories: List<String> = defaultToolDirectories(),
): PythonLauncher {
    val directory = lockDirectory(root) ?: return PythonLauncher()
    val uvLock = File(directory, UV_LOCK).isRegularFileNoFollow()
    val poetryLock = File(directory, POETRY_LOCK).isRegularFileNoFollow()
    if (uvLock && poetryLock) return PythonLauncher(failure = "both $UV_LOCK and $POETRY_LOCK exist")
    val pyproject = listOf(directory, root).distinct()
        .joinToString("\n") { ManifestSearch.readText(File(it, PYPROJECT)).orEmpty() }
    val lock = if (uvLock) UV_LOCK else POETRY_LOCK
    val launcher = if (uvLock) UV_LAUNCHER else POETRY_LAUNCHER
    val foreignTable = if (uvLock) POETRY_TABLE else UV_TABLE
    if (foreignTable.containsMatchIn(pyproject)) {
        return PythonLauncher(failure = "$lock conflicts with the other tool's tables in $PYPROJECT")
    }
    val tool = launcher.first()
    val resolved = resolvedTool(tool, path, pathExt, toolDirectories)
        ?: return PythonLauncher(failure = "$lock exists but $tool is not on PATH or in common install directories")
    return PythonLauncher(listOf(resolved) + launcher.drop(1))
}

internal fun CliCommand.inPythonLauncher(launcher: PythonLauncher): CliCommand {
    val plain = launcher.arguments.isEmpty() || arguments.firstOrNull() != "python" || arguments.getOrNull(1) == "-c"
    return if (plain) this else copy(arguments = launcher.arguments + arguments)
}

internal fun PythonLauncher.failureCommand(): CliCommand? = failure?.let {
    CliCommand(
        "Python environment unresolved",
        listOf(
            "python",
            "-c",
            "import sys; sys.stderr.write(\"Affected cannot select the managed Python environment: $it; " +
                "install the tool and remove conflicting markers.\\n\"); raise SystemExit(2)",
        ),
    )
}

private fun lockDirectory(root: File): File? {
    val chain = generateSequence(root.absoluteFile.normalize()) { it.parentFile }.toList()
    val repository = chain.indexOfFirst { File(it, ".git").exists() }.coerceAtLeast(0)
    return chain.take(repository + 1).firstOrNull { directory ->
        LOCKS.any { File(directory, it).isRegularFileNoFollow() } &&
            (directory == chain.first() || uvWorkspaceIncludes(directory, chain.first()))
    }
}

private fun uvWorkspaceIncludes(workspace: File, member: File): Boolean {
    if (!File(workspace, UV_LOCK).isRegularFileNoFollow()) return false
    val section = UV_WORKSPACE.find(ManifestSearch.readText(File(workspace, PYPROJECT)).orEmpty())
        ?.groupValues?.get(1) ?: return false
    val relative = workspace.toPath().relativize(member.toPath())
    val matches = { key: String ->
        workspaceGlobs(section, key).any { FileSystems.getDefault().getPathMatcher("glob:$it").matches(relative) }
    }
    return matches("members") && !matches("exclude")
}

private fun workspaceGlobs(section: String, key: String): List<String> =
    Regex("""(?s)\b$key\s*=\s*\[(.*?)]""").find(section)?.groupValues?.get(1)
        ?.let { QUOTED.findAll(it).map { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }.toList() }
        .orEmpty()

private fun resolvedTool(tool: String, path: String?, pathExt: String?, toolDirectories: List<String>): String? {
    if (resolveExecutable(tool, path, pathExt) != tool) return tool
    return resolveExecutable(tool, toolDirectories.joinToString(File.pathSeparator), pathExt).takeIf { it != tool }
}

private fun defaultToolDirectories(): List<String> {
    val home = System.getProperty("user.home").orEmpty()
    return listOf("$home/.local/bin", "$home/.cargo/bin", "/opt/homebrew/bin", "/usr/local/bin")
}

private const val PYPROJECT = "pyproject.toml"
private const val UV_LOCK = "uv.lock"
private const val POETRY_LOCK = "poetry.lock"
private val LOCKS = listOf(UV_LOCK, POETRY_LOCK)
private val UV_LAUNCHER = listOf("uv", "run", "--locked")
private val POETRY_LAUNCHER = listOf("poetry", "run")
private val UV_TABLE = Regex("""(?m)^\s*\[tool\.uv[.\]]""")
private val POETRY_TABLE = Regex("""(?m)^\s*\[tool\.poetry[.\]]""")
private val UV_WORKSPACE = Regex("""(?ms)^\s*\[tool\.uv\.workspace]\s*$(.*?)(?=^\s*\[|\z)""")
private val QUOTED = Regex(""""([^"]+)"|'([^']+)'""")
