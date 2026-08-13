package com.aspix2k.affected.build

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

class PythonBuildSystem : ChangeAwareSuspendingBuildSystem, AllFileChangesBuildSystem {

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

    override suspend fun runAndWaitSuspending(
        project: Project,
        root: String,
        tasks: List<String>,
        changes: BuildChanges,
    ): Boolean {
        val commands = withContext(Dispatchers.IO) { commands(project, root, tasks, changes) }
        return CommandRunner.runBatchAndWait(project, root, commands, "Affected Python")
    }

    private fun commands(project: Project, root: String, tasks: List<String>): List<CliCommand> {
        return pythonCommands(root, tasks, modules(project))
    }

    private fun commands(
        project: Project,
        root: String,
        tasks: List<String>,
        changes: BuildChanges,
    ): List<CliCommand> {
        val adapter = configuredPytestAdapter()
            ?: findPytestAdapter(Path.of(PathManager.getJarPathForClass(PythonBuildSystem::class.java)))
        return if (adapter == null) {
            pythonCommands(root, tasks, modules(project))
        } else {
            pythonCommands(root, tasks, modules(project), changes, adapter)
        }
    }

    private fun rootOf(project: Project): File? =
        project.basePath?.let(::File)?.takeIf { File(it, "pyproject.toml").isRegularFileNoFollow() }
}

private val PYTHON_TEST_DIRECTORIES = setOf("test", "tests")

internal fun pythonCommands(root: String, tasks: List<String>, modules: List<BuildModule>): List<CliCommand> {
    return resolvedPythonCommands(root, tasks, modules, null, null)
}

internal fun pythonCommands(
    root: String,
    tasks: List<String>,
    modules: List<BuildModule>,
    changes: BuildChanges,
    adapter: Path,
): List<CliCommand> = resolvedPythonCommands(root, tasks, modules, changes, adapter)

private fun resolvedPythonCommands(
    root: String,
    tasks: List<String>,
    modules: List<BuildModule>,
    changes: BuildChanges?,
    adapter: Path?,
): List<CliCommand> {
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
            val packages = paths.distinct()
            val context = changes?.let { pythonExactContext(root, packages, modules, it) }
            if (context == null || adapter == null || !adapter.isReadableRegularFile()) {
                CliCommand("pytest", listOf("python", "-m", "pytest") + packages)
            } else {
                CliCommand("pytest", listOf("python", adapter.toString(), context, "--") + packages)
            }
        }
    }
}

private fun pythonExactContext(
    root: String,
    packages: List<String>,
    modules: List<BuildModule>,
    changes: BuildChanges,
): String? = runCatching {
    if (!changes.comparedToBase || changes.files.isEmpty() || changes.files.size > MAX_PYTHON_CONTEXT_PATHS) return null
    if (changes.files.toSet() != changes.exactSelectionEligible) return null
    val rootPath = Path.of(root).toAbsolutePath().normalize()
    if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(rootPath)) return null
    val roots = modules.map { module ->
        module.contentRoots.singleOrNull()
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
            ?.relativeTo(rootPath)
            ?: return null
    }.distinct()
    val packageRoots = packages.map { packageName ->
        val directory = rootPath.resolve(packageName).normalize()
        directory.relativeTo(rootPath) ?: return null
    }
    val changed = changes.files.map { Path.of(it).toAbsolutePath().normalize().relativeTo(rootPath) ?: return null }
    val eligible = changes.exactSelectionEligible.map { path ->
        Path.of(path).toAbsolutePath().normalize().relativeTo(rootPath) ?: return null
    }
    val json = JsonObject().apply {
        addProperty("schema", PYTEST_CONTEXT_SCHEMA)
        add("roots", roots.toJsonArray())
        add("packages", packageRoots.toJsonArray())
        add("changes", changed.toJsonArray())
        add("eligible", eligible.toJsonArray())
    }.toString().toByteArray(StandardCharsets.UTF_8)
    if (json.size > MAX_PYTHON_CONTEXT_BYTES) return null
    Base64.getUrlEncoder().withoutPadding().encodeToString(json)
}.getOrNull()

private fun Path.relativeTo(root: Path): String? =
    takeIf { startsWith(root) }
        ?.let(root::relativize)
        ?.toString()
        ?.replace('\\', '/')
        ?.ifEmpty { "." }

private fun List<String>.toJsonArray(): JsonArray = JsonArray().also { array -> forEach(array::add) }

private fun Path.isReadableRegularFile(): Boolean =
    Files.isRegularFile(this, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(this) && !Files.isSymbolicLink(this)

private fun configuredPytestAdapter(): Path? = System.getProperty(PYTEST_ADAPTER_PROPERTY)
    ?.let(Path::of)
    ?.toAbsolutePath()
    ?.normalize()
    ?.takeIf { it.isReadableRegularFile() }

internal fun findPytestAdapter(classPath: Path): Path? {
    var directory = classPath.toAbsolutePath().normalize().let {
        if (Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)) it else it.parent
    } ?: return null
    repeat(MAX_PLUGIN_PARENT_DEPTH) {
        val adapter = directory.resolve(PYTEST_ADAPTER_PATH)
        if (adapter.isReadableRegularFile()) return adapter
        directory = directory.parent ?: return null
    }
    return null
}

private const val PYTEST_CONTEXT_SCHEMA = 1
private const val MAX_PYTHON_CONTEXT_PATHS = 256
private const val MAX_PYTHON_CONTEXT_BYTES = 12 * 1024
private const val MAX_PLUGIN_PARENT_DEPTH = 5
private const val PYTEST_ADAPTER_PROPERTY = "affected.test.pytestAdapter"
private const val PYTEST_ADAPTER_PATH = "agent/affected-pytest.py"
