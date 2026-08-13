package com.aspix2k.affected.build

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

class ComposerBuildSystem :
    ChangeAwareSuspendingBuildSystem,
    AllFileChangesBuildSystem,
    TransitiveTestConsumersBuildSystem,
    WorkspaceChangesBuildSystem {

    private data class Snapshot(val root: String, val stamp: String, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "COMPOSER"

    override val sourceExtensions: Set<String> = setOf("php", "json", "neon", "xml", "lock")

    override fun isPresent(project: Project): Boolean = rootOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = rootOf(project) ?: return emptyList()
        val stamp = combineFingerprints(
            ManifestSearch.fingerprint(
                root,
                listOf(
                    "composer.json", "composer.lock", "phpunit.xml", "phpunit.xml.dist", "phpunit.dist.xml",
                    "phpstan.neon", "phpstan.neon.dist", "phpstan.dist.neon", "psalm.xml", "psalm.xml.dist",
                ).flatMap { ManifestSearch.find(root, it) },
            ),
            ManifestSearch.layoutFingerprint(root) { it.name in COMPOSER_TEST_DIRECTORIES },
            ComposerPest.layoutFingerprint(root),
        )

        val rootPath = root.invariantSeparatorsPath
        if (stamp != null) cache.get()?.takeIf { it.root == rootPath && it.stamp == stamp }?.let { return it.modules }

        val discovered = runCatching { ComposerPackages.parse(root) }.getOrNull()
        val discovery = if (discovered.isNullOrEmpty()) {
            failClosedModules(root, ComposerPackages.fallbackTask(root), null, discovered)
        } else {
            ModuleDiscovery(discovered, complete = true)
        }
        if (stamp != null && discovery.complete) cache.set(Snapshot(rootPath, stamp, discovery.modules))
        return discovery.modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, commands(project, root, tasks), "Affected Composer")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, commands(project, root, tasks), "Affected Composer")

    override suspend fun runAndWaitSuspending(
        project: Project,
        root: String,
        tasks: List<String>,
        changes: BuildChanges,
    ): Boolean {
        if (!composerUsesPhpunitSelection(tasks)) {
            return CommandRunner.runBatchAndWait(project, root, commands(project, root, tasks), "Affected Composer")
        }
        val adapter = configuredPhpunitAdapter()
            ?: findPhpunitAdapter(Path.of(PathManager.getJarPathForClass(ComposerBuildSystem::class.java)))
            ?: return CommandRunner.runBatchAndWait(project, root, commands(project, root, tasks), "Affected Composer")
        val selective = withContext(Dispatchers.IO) {
            PhpunitSelectiveRun.create(project, Path.of(root), tasks, modules(project), changes, adapter)
        } ?: return CommandRunner.runBatchAndWait(project, root, commands(project, root, tasks), "Affected Composer")
        return try {
            val passed = CommandRunner.runBatchAndWait(project, root, selective.commands, "Affected Composer")
            passed && withContext(Dispatchers.IO) { selective.complete() }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { selective.close() }
        }
    }

    override fun requiresWorkspace(module: BuildModule, changes: BuildChanges): Boolean {
        return changes.files.any { pestWorkspaceChange(module.root, it) }
    }

    private fun commands(project: Project, root: String, tasks: List<String>): List<CliCommand> {
        return composerCommands(root, tasks, modules(project))
    }

    private fun rootOf(project: Project): File? =
        project.basePath?.let(::File)?.takeIf { File(it, "composer.json").isRegularFileNoFollow() }
}

private fun configuredPhpunitAdapter(): Path? = System.getProperty(PHPUNIT_ADAPTER_PROPERTY)
    ?.let(Path::of)
    ?.toAbsolutePath()
    ?.normalize()
    ?.takeIf(::readablePhpunitAdapter)

internal fun findPhpunitAdapter(classPath: Path): Path? {
    var directory = classPath.toAbsolutePath().normalize().let {
        if (Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)) it else it.parent
    } ?: return null
    repeat(MAX_PHPUNIT_PLUGIN_PARENT_DEPTH) {
        val adapter = directory.resolve(PHPUNIT_ADAPTER_PATH)
        if (readablePhpunitAdapter(adapter)) return adapter
        directory = directory.parent ?: return null
    }
    return null
}

private fun readablePhpunitAdapter(path: Path): Boolean =
    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(path) && !Files.isSymbolicLink(path)

private val COMPOSER_TEST_DIRECTORIES = setOf("test", "tests", "Tests")

internal fun pestWorkspaceChange(root: String, file: String): Boolean {
    val rootPath = File(root).toPath().toAbsolutePath().normalize()
    val filePath = File(file).toPath().toAbsolutePath().normalize()
    if (!filePath.startsWith(rootPath)) return false
    val relative = rootPath.relativize(filePath).joinToString("/")
    if (relative in PEST_BOOT_FILES) return true
    if (relative.startsWith("tests/Expectations/") || relative.startsWith("tests/Helpers/")) return true
    if (!relative.startsWith("tests/")) return false
    if (filePath.fileName.toString() == "Datasets.php") return true
    return relative.split('/').drop(1).dropLast(1).contains("Datasets")
}

private val PEST_BOOT_FILES = setOf(
    "tests/Pest.php",
    "tests/Expectations.php",
    "tests/Helpers.php",
    "tests/Datasets.php",
)

internal fun composerCommands(root: String, tasks: List<String>, modules: List<BuildModule>): List<CliCommand> {
    val planned = resolveComposerTasks(tasks, modules) ?: return emptyList()
    val resolved = resolveComposerPaths(root, planned) ?: return emptyList()
    return resolved.groupBy({ it.first }, { it.second }).map { (task, paths) ->
        when (task) {
            ComposerPackages.ANALYSE ->
                CliCommand("phpstan", listOf("php", "vendor/bin/phpstan", "analyse") + paths.distinct())
            ComposerPackages.PEST ->
                CliCommand("pest", pestArguments(root, paths.distinct()))
            ComposerPackages.TEST ->
                CliCommand("phpunit", listOf("php", "vendor/bin/phpunit") + paths.distinct())
            else -> return emptyList()
        }
    }
}

private fun resolveComposerTasks(tasks: List<String>, modules: List<BuildModule>): List<Pair<String, String>>? {
    val byName = modules.associateBy { it.executionId }
    return tasks.map { task ->
        val module = byName[task.substringBeforeLast(':')] ?: return null
        val taskName = task.substringAfterLast(':')
        if (module.testTask != taskName) return null
        taskName to (module.contentRoots.singleOrNull() ?: return null)
    }
}

private fun resolveComposerPaths(
    root: String,
    planned: List<Pair<String, String>>,
): List<Pair<String, String>>? {
    val pestDirectories = planned.filter { it.first == ComposerPackages.PEST }.map { File(it.second) }
    val pestSuites = if (pestDirectories.isEmpty()) {
        emptyMap()
    } else {
        ComposerPest.suiteDirectories(File(root), pestDirectories) ?: return null
    }
    val resolved = ArrayList<Pair<String, String>>()
    for ((task, directory) in planned) {
        val paths = if (task == ComposerPackages.PEST) {
            pestSuites[pathKey(directory)]?.takeIf { it.isNotEmpty() } ?: return null
        } else {
            listOf(File(directory))
        }
        for (path in paths) resolved += task to (composerPackagePath(root, path.path) ?: return null)
    }
    return resolved
}

private fun pathKey(path: String): String = File(path).toPath().toAbsolutePath().normalize().toString()

internal fun composerUsesPhpunitSelection(tasks: List<String>): Boolean =
    tasks.none { it.substringAfterLast(':') in PEST_TASKS }

private val PEST_TASKS = setOf(ComposerPackages.PEST)

internal fun pestArguments(root: String, paths: List<String>): List<String> {
    val cache = File(root, ".affected/pest-cache")
    cache.mkdirs()
    cache.setReadable(false, false)
    cache.setWritable(false, false)
    cache.setExecutable(false, false)
    cache.setReadable(true, true)
    cache.setWritable(true, true)
    cache.setExecutable(true, true)
    return listOf(
        "php",
        "vendor/bin/pest",
        "--cache-directory",
        cache.invariantSeparatorsPath,
        "--do-not-cache-result",
    ) + paths
}

internal fun composerPackagePath(root: String, directory: String): String? {
    val normalizedRoot = File(root).invariantSeparatorsPath.trimEnd('/')
    val normalizedDirectory = File(directory).invariantSeparatorsPath
    val relative = when {
        normalizedDirectory == normalizedRoot -> "."
        normalizedDirectory.startsWith("$normalizedRoot/") -> normalizedDirectory.removePrefix("$normalizedRoot/")
        else -> return null
    }
    return if (relative == ".") relative else "./$relative"
}

private const val PHPUNIT_ADAPTER_PROPERTY = "affected.test.phpunitAdapter"
private const val PHPUNIT_ADAPTER_PATH = "agent/affected-phpunit.php"
private const val MAX_PHPUNIT_PLUGIN_PARENT_DEPTH = 5
