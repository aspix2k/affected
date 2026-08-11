package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class PythonBuildSystem : SuspendingBuildSystem {

    private data class Snapshot(val root: String, val stamp: String, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "PYTHON"

    override val sourceExtensions: Set<String> = setOf("py", "pyi", "toml", "cfg", "ini", "lock")

    override fun isPresent(project: Project): Boolean = rootOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = rootOf(project) ?: return emptyList()
        val stamp = combineFingerprints(
            ManifestSearch.fingerprint(
                root,
                listOf("pyproject.toml", "mypy.ini", "setup.cfg", "uv.lock", "poetry.lock")
                    .flatMap { ManifestSearch.find(root, it) },
            ),
            ManifestSearch.layoutFingerprint(root) {
                it.name in PYTHON_TEST_DIRECTORIES || it.name.startsWith("test_") && it.extension == "py"
            },
        )

        val rootPath = root.invariantSeparatorsPath
        if (stamp != null) cache.get()?.takeIf { it.root == rootPath && it.stamp == stamp }?.let { return it.modules }

        val discovered = runCatching { PythonProjects.parse(root) }.getOrNull()
        val discovery = failClosedModules(root, PythonProjects.TEST, null, discovered)
        if (stamp != null && discovery.complete) cache.set(Snapshot(rootPath, stamp, discovery.modules))
        return discovery.modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, commands(project, root, tasks), "Affected Python")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, commands(project, root, tasks), "Affected Python")

    private fun commands(project: Project, root: String, tasks: List<String>): List<CliCommand> {
        return pythonCommands(root, tasks, modules(project))
    }

    private fun rootOf(project: Project): File? =
        project.basePath?.let(::File)?.takeIf { File(it, "pyproject.toml").isRegularFileNoFollow() }
}

private val PYTHON_TEST_DIRECTORIES = setOf("test", "tests")

internal fun pythonCommands(root: String, tasks: List<String>, modules: List<BuildModule>): List<CliCommand> {
    val byName = modules.associateBy { it.executionId }
    val resolved = tasks.map { task ->
        val name = task.substringBeforeLast(':')
        val directory = byName[name]?.contentRoots?.singleOrNull() ?: return emptyList()
        task.substringAfterLast(':') to directory.removePrefix("$root/").ifEmpty { "." }
    }
    return resolved.groupBy({ it.first }, { it.second }).map { (task, paths) ->
        if (task == PythonProjects.TYPECHECK) {
            CliCommand("mypy", listOf("python", "-m", "mypy") + paths.distinct())
        } else {
            CliCommand("pytest", listOf("python", "-m", "pytest") + paths.distinct())
        }
    }
}
