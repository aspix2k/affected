package com.aspix2k.affected.build

import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

class DotnetBuildSystem :
    ChangeAwareSuspendingBuildSystem,
    AllFileChangesBuildSystem,
    TransitiveTestConsumersBuildSystem {

    private data class Snapshot(val root: String, val stamp: String, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "DOTNET"

    override val sourceExtensions: Set<String> =
        setOf("cs", "fs", "vb", "csproj", "fsproj", "vbproj", "props", "targets", "sln", "slnx", "razor", "json")

    override fun isPresent(project: Project): Boolean = rootOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = rootOf(project) ?: return emptyList()
        val rootPath = root.invariantSeparatorsPath
        val stamp = ManifestSearch.fingerprint(root, manifests(root))
        if (stamp != null) cache.get()?.takeIf { it.root == rootPath && it.stamp == stamp }?.let { return it.modules }

        val discovered = runCatching { DotnetProjects.parse(root) }.getOrNull()
        val discovery = failClosedModules(
            root,
            DotnetProjects.TEST,
            DotnetProjects.COMPILE,
            discovered,
        )
        if (stamp != null && discovery.complete) cache.set(Snapshot(rootPath, stamp, discovery.modules))
        return discovery.modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, dotnetCommands(root, tasks), "Affected .NET")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, dotnetCommands(root, tasks), "Affected .NET")

    override suspend fun runAndWaitSuspending(
        project: Project,
        root: String,
        tasks: List<String>,
        changes: BuildChanges,
    ): Boolean {
        val selective = withContext(Dispatchers.IO) {
            DotnetSelectiveRun.create(project, Path.of(root), tasks, modules(project), changes)
        }
        if (selective == null) {
            return CommandRunner.runBatchAndWait(project, root, dotnetCommands(root, tasks), "Affected .NET")
        }
        return try {
            val passed = CommandRunner.runBatchAndWait(project, root, selective.commands, "Affected .NET")
            passed && selective.complete()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { selective.close() }
        }
    }

    private fun rootOf(project: Project): File? {
        val base = project.basePath?.let(::File) ?: return null
        val children = base.listFiles() ?: return null
        return base.takeIf { _ ->
            children.any {
                it.extension.lowercase() in SOLUTION_EXTENSIONS && it.isRegularFileNoFollow() ||
                    it.name == "global.json" && it.isRegularFileNoFollow() ||
                    DotnetProjects.isProjectFile(it)
            }
        }
    }

    private fun manifests(root: File): List<File> =
        listOf("csproj", "fsproj", "vbproj", "props", "targets", "sln", "slnx")
            .flatMap { ManifestSearch.findByExtension(root, it) } +
            listOf("global.json", "NuGet.Config").flatMap { ManifestSearch.find(root, it) }
}

private class DotnetSelectiveRun private constructor(
    val commands: List<CliStep>,
    private val projects: List<DotnetProjectRun>,
) {

    suspend fun complete(): Boolean {
        for (project in projects) {
            currentCoroutineContext().ensureActive()
            val valid = runInterruptible(Dispatchers.IO) { project.complete() }
            currentCoroutineContext().ensureActive()
            if (!valid) return false
        }
        return true
    }

    fun close() {
        projects.forEach(DotnetProjectRun::close)
    }

    companion object {
        fun create(
            project: Project,
            root: Path,
            tasks: List<String>,
            modules: List<BuildModule>,
            changes: BuildChanges,
        ): DotnetSelectiveRun? = runCatching {
            val byExecutionId = modules.associateBy(BuildModule::executionId)
            require(tasks.all { task -> task.substringBeforeLast(':') in byExecutionId })
            val systemDirectory = PathManager.getSystemDir()
                .resolve(DOTNET_CACHE_DIRECTORY)
                .resolve(project.locationHash)
                .resolve("dotnet")
            val cache = secureDotnetDirectory(
                systemDirectory,
            )
            val projectRuns = ArrayList<DotnetProjectRun>()
            val commands = ArrayList<CliStep>()
            tasks.forEach { task ->
                val executionId = task.substringBeforeLast(':')
                val verb = task.substringAfterLast(':')
                val module = byExecutionId.getValue(executionId)
                if (verb == DotnetProjects.COMPILE) {
                    commands += dotnetBuildCommand(executionId)
                } else {
                    require(verb == DotnetProjects.TEST && executionId != ".")
                    val dependencies = dependencyInputs(module, modules)
                    val run = DotnetProjectRun(
                        root,
                        executionId,
                        dependencies.roots,
                        dependencies.projects,
                        changes,
                        cache.resolve(sha256(executionId)),
                    )
                    projectRuns += run
                    commands += dotnetBuildCommand(executionId)
                    commands += DeferredCliCommand("dotnet test $executionId", run::resolve)
                }
            }
            DotnetSelectiveRun(commands, projectRuns)
        }.getOrNull()
    }
}

private class DotnetProjectRun(
    private val root: Path,
    private val project: String,
    private val productionRoots: Set<Path>,
    private val productionProjects: Set<Path>,
    private val changes: BuildChanges,
    cache: Path,
) {

    private val directory = secureDotnetDirectory(cache)
    private val store = DotnetTestBaselineStore(directory.resolve("maps"))
    private var state: DotnetRunState = DotnetRunState.Unresolved

    @Synchronized
    fun resolve(): List<String>? {
        check(state == DotnetRunState.Unresolved)
        val metadata = readDotnetProjectMetadata(root, project, productionProjects)
        if (metadata == null) {
            state = DotnetRunState.Unsupported
            return dotnetCommands(root.toString(), listOf("$project:${DotnetProjects.TEST}")).single().arguments
        }
        val baseline = store.read()
        val before = analyzeDotnetProject(metadata, baseline?.classes?.keys.orEmpty(), directory)
        if (before == null) {
            state = DotnetRunState.Unsupported
            return dotnetCommands(root.toString(), listOf("$project:${DotnetProjects.TEST}")).single().arguments
        }
        val current = baseline?.let { before.snapshot(it.tests) }
        val selection = if (dotnetChangedSourcesAreOwned(root, productionProjects, changes)) {
            selectDotnetTests(root, productionRoots, current, baseline, changes)
        } else {
            selectUnchangedDotnetConsumer(root, current, baseline, changes)
        }
        if (selection == DotnetTestSelection.Empty) {
            state = DotnetRunState.Selected(before, selection, null)
            return null
        }
        val report = newDotnetReport(directory.resolve("reports"))
        state = DotnetRunState.Selected(before, selection, report)
        return dotnetTestArguments(project, selection, report)
    }

    @Synchronized
    fun complete(): Boolean = when (val resolved = state) {
        DotnetRunState.Unresolved -> false
        DotnetRunState.Unsupported -> true
        is DotnetRunState.Selected -> complete(resolved)
    }

    @Synchronized
    fun close() {
        val report = (state as? DotnetRunState.Selected)?.report ?: return
        runCatching { Files.deleteIfExists(report) }
        runCatching { Files.deleteIfExists(report.parent) }
    }

    private fun complete(resolved: DotnetRunState.Selected): Boolean {
        if (resolved.selection == DotnetTestSelection.Empty) {
            return dotnetSelectionCompleted(
                resolved.selection,
                resolved.before,
                analyzedNow(resolved.before.classes.keys),
                emptySet(),
            )
        }
        val report = resolved.report?.let(::readDotnetTestReport)
            ?: return resolved.selection == DotnetTestSelection.Full
        return when (val selection = resolved.selection) {
            DotnetTestSelection.Empty -> false
            is DotnetTestSelection.Exact -> dotnetSelectionCompleted(
                selection,
                resolved.before,
                analyzedNow(resolved.before.classes.keys),
                report.tests.keys,
            )
            DotnetTestSelection.Full -> {
                val metadata = readDotnetProjectMetadata(root, project, productionProjects)
                val after = metadata?.let { analyzeDotnetProject(it, report.tests.values.toSet(), directory) }
                promoteDotnetBaseline(
                    store,
                    resolved.before,
                    after,
                    resolved.report,
                    full = true,
                    passed = true,
                )
                true
            }
        }
    }

    private fun analyzedNow(classes: Set<String>): DotnetAnalyzedState? {
        val metadata = readDotnetProjectMetadata(root, project, productionProjects) ?: return null
        return analyzeDotnetProject(metadata, classes, directory)
    }
}

internal fun dotnetSelectionCompleted(
    selection: DotnetTestSelection,
    before: DotnetAnalyzedState,
    after: DotnetAnalyzedState?,
    executedTests: Set<String>,
): Boolean = before == after && when (selection) {
    DotnetTestSelection.Empty -> executedTests.isEmpty()
    is DotnetTestSelection.Exact -> executedTests == selection.tests.toSet()
    DotnetTestSelection.Full -> false
}

private sealed interface DotnetRunState {
    data object Unresolved : DotnetRunState
    data object Unsupported : DotnetRunState
    data class Selected(
        val before: DotnetAnalyzedState,
        val selection: DotnetTestSelection,
        val report: Path?,
    ) : DotnetRunState
}

private data class DotnetDependencyInputs(
    val roots: Set<Path>,
    val projects: Set<Path>,
)

private fun dependencyInputs(module: BuildModule, modules: List<BuildModule>): DotnetDependencyInputs {
    val byKey = modules.associateBy(BuildModule::key)
    val pending = ArrayDeque(module.dependencies)
    val visited = LinkedHashSet<String>()
    val roots = LinkedHashSet<Path>()
    val projects = LinkedHashSet<Path>()
    while (pending.isNotEmpty()) {
        val key = pending.removeFirst()
        if (!visited.add(key)) continue
        val dependency = byKey[key] ?: return DotnetDependencyInputs(emptySet(), emptySet())
        dependency.contentRoots.mapTo(roots, Path::of)
        projects.add(Path.of(dependency.root).resolve(dependency.executionId))
        pending += dependency.dependencies
    }
    return DotnetDependencyInputs(roots, projects)
}

internal fun dotnetBuildCommand(project: String): CliCommand {
    val selection = if (project == ".") emptyList() else listOf(project)
    return CliCommand("dotnet build $project", listOf("dotnet", "build") + selection)
}

internal fun dotnetTestArguments(
    project: String,
    selection: DotnetTestSelection? = null,
    report: Path? = null,
): List<String> {
    val arguments = mutableListOf("dotnet", "test", project, "--no-build", "--no-restore")
    if (report != null) {
        arguments += listOf(
            "--results-directory",
            report.parent.toString(),
            "--logger",
            "trx;LogFileName=${report.fileName}",
        )
    }
    if (selection is DotnetTestSelection.Exact) {
        arguments += listOf("--filter", dotnetFilter(selection.tests))
    }
    return arguments
}

private val SOLUTION_EXTENSIONS = setOf("sln", "slnx")
private const val DOTNET_CACHE_DIRECTORY = "affected"

internal fun dotnetCommands(root: String, tasks: List<String>): List<CliCommand> = tasks.map { task ->
    val project = task.substringBeforeLast(':')
    val verb = if (task.substringAfterLast(':') == DotnetProjects.COMPILE) "build" else "test"
    val selection = when {
        project == "." -> emptyList()
        verb == "test" && usesMicrosoftTestingPlatform(root) -> listOf("--project", project)
        else -> listOf(project)
    }
    CliCommand("dotnet $verb $project", listOf("dotnet", verb) + selection)
}

internal fun usesMicrosoftTestingPlatform(root: String): Boolean = runCatching {
    val global = File(root, "global.json").takeIf(File::isRegularFileNoFollow) ?: return false
    val text = ManifestSearch.readText(global) ?: return false
    JsonParser.parseString(text).asJsonObject
        .getAsJsonObject("test")
        ?.get("runner")
        ?.takeIf { it.isJsonPrimitive }
        ?.asString
        ?.equals("Microsoft.Testing.Platform", ignoreCase = true) == true
}.getOrDefault(false)
