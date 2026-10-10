package com.aspix2k.affected

import com.aspix2k.affected.build.AllFileChangesBuildSystem
import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.ChangeAwareEngineBuildSystem
import com.aspix2k.affected.build.EngineBuildSystem
import com.aspix2k.affected.build.EngineBuildSystems
import com.aspix2k.affected.build.FileWorkspace
import com.aspix2k.affected.build.ManifestSearch
import com.aspix2k.affected.build.NamedSourceBuildSystem
import com.aspix2k.affected.build.SourceRootsBuildSystem
import com.aspix2k.affected.build.Workspace
import com.aspix2k.affected.build.capability
import com.aspix2k.affected.build.gradle.GRADLE_SYSTEM_ID
import com.aspix2k.affected.build.gradle.isGradleRoot
import com.aspix2k.affected.build.maven.MAVEN_SYSTEM_ID
import com.aspix2k.affected.build.process.ProcessHost
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class EngineRequest(
    val directory: File,
    val baseBranch: String,
    val cacheDirectory: Path,
    val testDependents: Boolean = false,
    val checkConsumers: Boolean = false,
    val stopAfterFirstFailure: Boolean = false,
    val assumedChanges: List<String> = emptyList(),
    val output: (text: String, error: Boolean) -> Unit = { _, _ -> },
)

enum class EngineBlocker {
    NOT_A_GIT_REPOSITORY,
    NO_COMPARISON_BASE,
    UNSUPPORTED_BUILD_SYSTEM,
    NO_BUILD_SYSTEM,
    UNRESOLVED_CHANGES,
}

class EngineAudit internal constructor(
    val failedInSelection: List<TaskGroup>,
    val failedInFullRun: List<TaskGroup>,
    val missed: List<TaskGroup>,
    val selectionMillis: Long,
    val fullRunMillis: Long,
)

class EnginePlan internal constructor(
    val plan: Plan,
    val systems: List<BuildSystemSummary>,
    val changedFiles: List<File>,
    val unresolved: List<File>,
    val blocker: EngineBlocker?,
    internal val testModules: List<ModuleInfo>,
    internal val changes: BuildChanges,
    internal val workspace: Workspace,
    internal val present: List<EngineBuildSystem>,
) {
    val everyTest: Plan = TaskPlanner.plan(testModules, emptyList())
}

object Engine {

    fun plan(request: EngineRequest): EnginePlan = plan(request, null)

    internal fun plan(request: EngineRequest, host: ProcessHost?): EnginePlan {
        var collected = BuildChanges(emptyList(), emptySet(), comparedToBase = false)
        val workspace = FileWorkspace(
            request.directory,
            request.cacheDirectory,
            request.stopAfterFirstFailure,
            { collected },
            request.output,
            host,
        )
        val present = EngineBuildSystems.all().filter { it.isPresent(workspace) }
        val graph =
            ModuleGraph(present.flatMap { system -> system.modules(workspace).map { ModuleGraph.Node(it, system) } })
        val changes = collect(request, present, sourceRoots(request.directory, graph))
        collected = changes.toBuildChanges()
        val plans = verificationPlans(graph, changes, graph.owners(changes), request.testDependents)
        val blocker = when {
            !changes.gitUsable -> EngineBlocker.NOT_A_GIT_REPOSITORY
            changes.baseUnresolved || changes.resolvedBranch != request.baseBranch ->
                EngineBlocker.NO_COMPARISON_BASE
            hasUnsupportedBuild(request.directory, graph) -> EngineBlocker.UNSUPPORTED_BUILD_SYSTEM
            present.isEmpty() -> EngineBlocker.NO_BUILD_SYSTEM
            plans.unresolved.isNotEmpty() -> EngineBlocker.UNRESOLVED_CHANGES
            else -> null
        }
        val testModules = graph.all().filter { it.hasTests }.map { it.info() }
        return EnginePlan(
            if (request.checkConsumers) plans.withConsumers else plans.testsOnly,
            graph.systemSummaries(),
            changes.files,
            plans.unresolved,
            blocker,
            testModules,
            collected,
            workspace,
            present,
        )
    }

    suspend fun run(plan: EnginePlan): Boolean = plan.workspace.root != null &&
        failed(plan, plan.plan, plan.workspace.stopAfterFirstFailure, narrowed = true).isEmpty()

    suspend fun runEveryTest(plan: EnginePlan): Boolean = plan.workspace.root != null &&
        failed(plan, plan.everyTest, stopAfterFirstFailure = false, narrowed = false).isEmpty()

    suspend fun failedTestModules(plan: EnginePlan): List<ModuleInfo> =
        failed(plan, plan.everyTest, stopAfterFirstFailure = false, narrowed = false).flatMap { group ->
            val modules = plan.testModules.filter { it.systemId == group.systemId }
                .let { system -> system.filter { it.executionRoot == group.root }.ifEmpty { system } }
            modules.singleOrNull()?.let(::listOf) ?: modules.filter { module ->
                val alone = TaskPlanner.plan(listOf(module), emptyList())
                failed(plan, alone, stopAfterFirstFailure = false, narrowed = false).isNotEmpty()
            }
        }

    suspend fun audit(plan: EnginePlan): EngineAudit {
        val started = System.nanoTime()
        val failedInSelection = failed(plan, plan.plan, stopAfterFirstFailure = false, narrowed = true)
        val selected = System.nanoTime()
        val failedInFullRun = failed(plan, plan.everyTest, stopAfterFirstFailure = false, narrowed = false)
        val finished = System.nanoTime()
        val explained = failedInSelection.flatMapTo(HashSet()) { group ->
            group.tasks.map { TaskKey(group.systemId, group.root, it) }
        }
        val missed = failedInFullRun.mapNotNull { group ->
            val unexplained = group.copy(
                tasks = group.tasks.filterNot { TaskKey(group.systemId, group.root, it) in explained },
            )
            when (unexplained.tasks.size) {
                0 -> null
                group.tasks.size -> group
                else -> unexplained.takeIf {
                    failed(plan, Plan(listOf(it), 0, 0), stopAfterFirstFailure = false, narrowed = false).isNotEmpty()
                }
            }
        }
        return EngineAudit(
            failedInSelection,
            failedInFullRun,
            missed,
            TimeUnit.NANOSECONDS.toMillis(selected - started),
            TimeUnit.NANOSECONDS.toMillis(finished - selected),
        )
    }

    private suspend fun failed(
        plan: EnginePlan,
        tasks: Plan,
        stopAfterFirstFailure: Boolean,
        narrowed: Boolean,
    ): List<TaskGroup> {
        val projectRoot = plan.workspace.root?.toPath() ?: return tasks.groups
        val failed = ArrayList<TaskGroup>()
        for (group in tasks.groups) {
            val system = plan.present.firstOrNull { it.id == group.systemId }
            val groupPassed = system != null && group.runInPlannedExecutionRoot(projectRoot, onInvalid = {}) {
                (system as? ChangeAwareEngineBuildSystem)?.takeIf { narrowed }
                    ?.runAndWait(plan.workspace, group.root, group.tasks, plan.changes)
                    ?: system.runAndWait(plan.workspace, group.root, group.tasks)
            }
            if (!groupPassed) failed += group
            if (!groupPassed && stopAfterFirstFailure) break
        }
        return failed
    }

    private fun sourceRoots(directory: File, graph: ModuleGraph): Set<String> {
        val base = directory.absoluteFile.invariantSeparatorsPath.trimEnd('/')
        return graph.all().flatMapTo(HashSet()) { node ->
            node.system.capability<SourceRootsBuildSystem>()?.sourceRoots(node.module).orEmpty()
                .mapNotNull { root -> root.takeIf { it.startsWith("$base/") }?.removePrefix("$base/") }
        }
    }

    private fun collect(
        request: EngineRequest,
        present: List<EngineBuildSystem>,
        sourceRoots: Set<String>,
    ): ChangeSet {
        val includeAllFiles = present.any { it.capability<AllFileChangesBuildSystem>() != null }
        val extensions =
            present.flatMapTo(HashSet()) { it.sourceExtensions }.ifEmpty { ChangeAnalyzer.DEFAULT_EXTENSIONS }
        val names = present.mapNotNull { it.capability<NamedSourceBuildSystem>() }
            .flatMapTo(HashSet()) { it.sourceFileNames }
        val declared = DeclaredDependencies.read(request.directory)
        val analyzer = ChangeAnalyzer(
            request.directory,
            request.baseBranch,
            extensions,
            includeAllFiles,
            sourceFileNames = names,
            sourceRoots = if (includeAllFiles) emptySet() else sourceRoots,
            declaredPaths = declared?.mapTo(HashSet()) { it.path },
            assumedPaths = request.assumedChanges,
        )
        if (!analyzer.isUsable()) {
            return ChangeSet(emptyList(), emptySet(), emptySet(), comparedToBase = false, gitUsable = false)
        }
        val files = analyzer.againstBase()
        return ChangeSet(
            files,
            analyzer.apiTouchedAmong(files),
            analyzer.modifiedAgainstBase(),
            comparedToBase = analyzer.hasComparisonBase(),
            baseUnresolved = !analyzer.hasComparisonBase(),
            resolvedBranch = analyzer.resolvedBranch(),
            mergeBase = analyzer.comparisonBase(),
            declaredOwners = declared?.let { dependencies ->
                files.associateWith { DeclaredDependencies.owners(request.directory, dependencies, it) }
            },
            outsideSources = if (includeAllFiles) {
                filesOutsideSources(files, request.directory, extensions, names, sourceRoots)
            } else {
                emptySet()
            },
        )
    }
}

private fun hasUnsupportedBuild(root: File, graph: ModuleGraph): Boolean {
    val reactor = graph.all().filter { it.system.id == MAVEN_SYSTEM_ID }
        .flatMapTo(HashSet()) { node -> node.module.contentRoots.map { File(it).absoluteFile } }
    if (ManifestSearch.find(root, setOf("pom.xml")).any { it.parentFile.absoluteFile !in reactor }) return true
    val settings = ManifestSearch.find(root, GRADLE_SETTINGS_FILES).map { it.parentFile.absoluteFile }
    val scripts = ManifestSearch.find(root, GRADLE_BUILD_FILES)
    val known = graph.all().filter { it.system.id == GRADLE_SYSTEM_ID }
        .mapTo(HashSet()) { File(it.module.root).absoluteFile }
    return if (isGradleRoot(root)) {
        settings.any { it != root.absoluteFile && it !in known && !(it.name == "buildSrc" && it.parentFile in known) }
    } else {
        settings.isNotEmpty() || scripts.isNotEmpty()
    }
}

private val GRADLE_SETTINGS_FILES = setOf("settings.gradle", "settings.gradle.kts")
private val GRADLE_BUILD_FILES = setOf("build.gradle", "build.gradle.kts")
