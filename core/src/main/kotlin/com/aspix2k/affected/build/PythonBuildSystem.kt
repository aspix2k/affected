package com.aspix2k.affected.build

import com.aspix2k.affected.ProjectChanges
import com.aspix2k.affected.toBuildChanges
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
        if (stamp != null && discovery.complete) {
            cache.retainBuildSnapshot(Snapshot(rootPath, stamp, discovery.modules), discovery.modules.size)
        }
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
    ): List<CliStep> {
        val directory = File(root)
        val discovered = modules(project)
        val runner = pythonTestRunner(directory)
        val adapter = configuredPythonAdapter(runner)
            ?: findPythonAdapter(runner, Path.of(PathManager.getJarPathForClass(PythonBuildSystem::class.java)))
        return when {
            adapter == null -> {
                pythonCommands(root, tasks, discovered, changes)
            }
            runner == PythonTestRunner.UNITTEST -> {
                pythonDeferredCommands(root, tasks, discovered, changes, adapter, runner) {
                    ProjectChanges.collect(project).toBuildChanges()
                }
            }
            else -> {
                pythonCommands(root, tasks, discovered, changes, adapter)
            }
        }
    }

    private fun rootOf(project: Project): File? =
        project.basePath?.let(::File)?.let { base ->
            nestedBuildRoot(base) { File(it, "pyproject.toml").isRegularFileNoFollow() }
        }
}

private val PYTHON_TEST_DIRECTORIES = setOf("test", "tests")
private val PYTHON_GENERATED_DIRECTORIES = setOf(
    ".mypy_cache",
    ".nox",
    ".pytest_cache",
    ".tox",
    ".venv",
    "__pycache__",
    "build",
    "dist",
    "generated",
    "out",
    "venv",
)

internal fun pythonCommands(root: String, tasks: List<String>, modules: List<BuildModule>): List<CliCommand> {
    return resolvedPythonCommands(root, tasks, modules, null, null)
}

internal fun pythonCommands(
    root: String,
    tasks: List<String>,
    modules: List<BuildModule>,
    changes: BuildChanges,
): List<CliCommand> = resolvedPythonCommands(root, tasks, modules, changes, null)

internal fun pythonCommands(
    root: String,
    tasks: List<String>,
    modules: List<BuildModule>,
    changes: BuildChanges,
    adapter: Path,
): List<CliCommand> = resolvedPythonCommands(root, tasks, modules, changes, adapter)

internal fun resolvedPythonCommands(
    root: String,
    tasks: List<String>,
    modules: List<BuildModule>,
    changes: BuildChanges?,
    adapter: Path?,
    unittestAdapterFallback: Boolean = false,
    runner: PythonTestRunner = pythonTestRunner(File(root)),
): List<CliCommand> {
    val byName = modules.associateBy { it.executionId }
    val rootPath = Path.of(root).toAbsolutePath().normalize()
    val resolved = tasks.map { task ->
        val name = task.substringBeforeLast(':')
        val directory = byName[name]?.contentRoots?.singleOrNull() ?: return emptyList()
        val directoryPath = Path.of(directory).toAbsolutePath().normalize()
        if (!directoryPath.startsWith(rootPath)) return emptyList()
        val relative = rootPath.relativize(directoryPath).toString().replace('\\', '/').ifEmpty { "." }
        task.substringAfterLast(':') to relative
    }
    return resolved.groupBy({ it.first }, { it.second }).flatMap { (task, paths) ->
        if (task == PythonProjects.TYPECHECK) {
            listOf(CliCommand("mypy", listOf("python", "-m", "mypy") + paths.distinct()))
        } else {
            pythonTestCommands(root, paths.distinct(), modules, changes, adapter, unittestAdapterFallback, runner)
        }
    }
}

private fun pythonTestCommands(
    root: String,
    packages: List<String>,
    modules: List<BuildModule>,
    changes: BuildChanges?,
    adapter: Path?,
    unittestAdapterFallback: Boolean,
    runner: PythonTestRunner,
): List<CliCommand> {
    if (runner == PythonTestRunner.UNKNOWN) {
        return listOf(CliCommand("Python test runner unresolved", listOf("python", "-c", PYTHON_RUNNER_FAILURE)))
    }
    if (runner == PythonTestRunner.UNITTEST) {
        return unittestCommands(root, packages, changes, adapter, unittestAdapterFallback)
    }
    val context = changes?.let { pythonExactContext(root, packages, modules, it) }
    return if (context == null || adapter == null || !adapter.isReadableRegularFile()) {
        listOf(CliCommand("pytest", listOf("python", "-m", "pytest") + packages))
    } else {
        listOf(CliCommand("pytest", listOf("python", adapter.toString(), context, "--") + packages))
    }
}

private fun unittestCommands(
    root: String,
    packages: List<String>,
    changes: BuildChanges?,
    adapter: Path?,
    adapterFallback: Boolean,
): List<CliCommand> {
    val selected = changes?.let { selectUnittestFiles(root, packages, it) }
    val context = when {
        selected != null -> unittestContext(packages, selected)
        adapterFallback -> unittestContext(packages, emptyList())
        else -> null
    }
    if (adapter != null && adapter.isReadableRegularFile()) {
        if (context != null) {
            return listOf(CliCommand("unittest", listOf("python", adapter.toString(), context)))
        }
        if (adapterFallback) {
            return listOf(
                CliCommand(
                    "unittest package set unresolved",
                    listOf("python", "-c", UNITTEST_CONTEXT_FAILURE),
                ),
            )
        }
    }
    return fullUnittestCommands(packages)
}

private fun fullUnittestCommands(packages: List<String>): List<CliCommand> = packages.map { path ->
    CliCommand(
        "unittest $path",
        listOf("python", "-m", "unittest", "discover", "-s", path, "-t", "."),
    )
}

private fun selectUnittestFiles(
    root: String,
    packages: List<String>,
    changes: BuildChanges,
): List<String>? = runCatching {
    require(changes.comparedToBase)
    require(changes.files.isNotEmpty() && changes.files.size <= MAX_PYTHON_CONTEXT_PATHS)
    require(changes.files.toSet() == changes.exactSelectionEligible)
    val rootPath = Path.of(root).toAbsolutePath().normalize()
    require(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(rootPath))
    val roots = packages.map { packageName ->
        val directory = rootPath.resolve(packageName).normalize()
        require(directory.startsWith(rootPath))
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory))
        require(symlinkFreePythonPath(rootPath, directory))
        directory
    }
    val selected = changes.files.map { raw ->
        val requested = Path.of(raw).toAbsolutePath().normalize()
        require(requested.startsWith(rootPath) && symlinkFreePythonPath(rootPath, requested))
        require(Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(requested))
        val real = requested.toRealPath(LinkOption.NOFOLLOW_LINKS)
        require(real.startsWith(rootPath))
        require(isPythonTestModule(real.toFile()))
        require(rootPath.relativize(real).none { it.toString().lowercase() in PYTHON_GENERATED_DIRECTORIES })
        require(roots.count { real.startsWith(it) } == 1)
        val relative = rootPath.relativize(real).toString().replace('\\', '/')
        require(relative.isNotEmpty() && !relative.startsWith("../"))
        relative
    }.distinct().sorted()
    require(selected.isNotEmpty())
    require(
        packages.all { packageName ->
            selected.any { relative ->
                packageName == "." || relative == packageName || relative.startsWith("$packageName/")
            }
        },
    )
    selected
}.getOrNull()

private fun unittestContext(packages: List<String>, selected: List<String>): String? = runCatching {
    val json = JsonObject().apply {
        addProperty("schema", UNITTEST_CONTEXT_SCHEMA)
        add("packages", packages.toJsonArray())
        add("selected", selected.toJsonArray())
    }.toString().toByteArray(StandardCharsets.UTF_8)
    require(json.size <= MAX_UNITTEST_CONTEXT_BYTES)
    Base64.getUrlEncoder().withoutPadding().encodeToString(json)
}.getOrNull()

private fun symlinkFreePythonPath(root: Path, target: Path): Boolean {
    if (!target.startsWith(root)) return false
    var current = root
    for (segment in root.relativize(target)) {
        current = current.resolve(segment)
        if (Files.isSymbolicLink(current)) return false
    }
    return true
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

private fun configuredUnittestAdapter(): Path? = System.getProperty(UNITTEST_ADAPTER_PROPERTY)
    ?.let(Path::of)
    ?.toAbsolutePath()
    ?.normalize()
    ?.takeIf { it.isReadableRegularFile() }

private fun configuredPythonAdapter(runner: PythonTestRunner): Path? = when (runner) {
    PythonTestRunner.PYTEST -> configuredPytestAdapter()
    PythonTestRunner.UNITTEST -> configuredUnittestAdapter()
    PythonTestRunner.UNKNOWN -> null
}

internal fun findPytestAdapter(classPath: Path): Path? = findPythonAdapter(classPath, PYTEST_ADAPTER_PATH)

internal fun findPythonAdapter(root: File, classPath: Path): Path? = findPythonAdapter(
    pythonTestRunner(root),
    classPath,
)

private fun findPythonAdapter(runner: PythonTestRunner, classPath: Path): Path? = findPythonAdapter(
    classPath,
    when (runner) {
        PythonTestRunner.PYTEST -> PYTEST_ADAPTER_PATH
        PythonTestRunner.UNITTEST -> UNITTEST_ADAPTER_PATH
        PythonTestRunner.UNKNOWN -> return null
    },
)

private fun findPythonAdapter(classPath: Path, adapterPath: String): Path? {
    var directory = classPath.toAbsolutePath().normalize().let {
        if (Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)) it else it.parent
    } ?: return null
    repeat(MAX_PLUGIN_PARENT_DEPTH) {
        val adapter = directory.resolve(adapterPath)
        if (adapter.isReadableRegularFile()) return adapter
        directory = directory.parent ?: return null
    }
    return null
}

private const val PYTEST_CONTEXT_SCHEMA = 1
private const val UNITTEST_CONTEXT_SCHEMA = 1
private const val MAX_PYTHON_CONTEXT_PATHS = 256
private const val MAX_PYTHON_CONTEXT_BYTES = 12 * 1024
private const val MAX_UNITTEST_CONTEXT_BYTES = 12 * 1024
private const val MAX_PLUGIN_PARENT_DEPTH = 5
private const val PYTEST_ADAPTER_PROPERTY = "affected.test.pytestAdapter"
private const val UNITTEST_ADAPTER_PROPERTY = "affected.test.unittestAdapter"
private const val PYTEST_ADAPTER_PATH = "agent/affected-pytest.py"
private const val UNITTEST_ADAPTER_PATH = "agent/affected-unittest.py"
private const val PYTHON_RUNNER_FAILURE =
    "import sys; sys.stderr.write(\"Affected could not safely determine whether this project uses " +
        "pytest or unittest; remove test-tree symlinks or declare pytest.\\n\"); raise SystemExit(2)"
private const val UNITTEST_CONTEXT_FAILURE =
    "import sys; sys.stderr.write(\"Affected could not safely encode the unittest package set; " +
        "reduce the number or depth of Python package roots.\\n\"); raise SystemExit(2)"
