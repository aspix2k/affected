package com.aspix2k.affected

import com.aspix2k.affected.build.AllFileChangesBuildSystem
import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.ChangeAwareEngineBuildSystem
import com.aspix2k.affected.build.EngineBuildSystem
import com.aspix2k.affected.build.EngineBuildSystems
import com.aspix2k.affected.build.FileWorkspace
import com.aspix2k.affected.build.ManifestSearch
import com.aspix2k.affected.build.NamedSourceBuildSystem
import com.aspix2k.affected.build.Workspace
import com.aspix2k.affected.build.capability
import java.io.File
import java.nio.file.Path

class EngineRequest(
    val directory: File,
    val baseBranch: String,
    val cacheDirectory: Path,
    val testDependents: Boolean = false,
    val checkConsumers: Boolean = false,
    val stopAfterFirstFailure: Boolean = false,
    val output: (text: String, error: Boolean) -> Unit = { _, _ -> },
)

enum class EngineBlocker {
    NOT_A_GIT_REPOSITORY,
    NO_COMPARISON_BASE,
    UNSUPPORTED_BUILD_SYSTEM,
    NO_BUILD_SYSTEM,
    UNRESOLVED_CHANGES,
}

class EnginePlan internal constructor(
    val plan: Plan,
    val systems: List<BuildSystemSummary>,
    val changedFiles: List<File>,
    val unresolved: List<File>,
    val blocker: EngineBlocker?,
    internal val changes: BuildChanges,
    internal val workspace: Workspace,
    internal val present: List<EngineBuildSystem>,
)

object Engine {

    fun plan(request: EngineRequest): EnginePlan {
        var collected = BuildChanges(emptyList(), emptySet(), comparedToBase = false)
        val workspace = FileWorkspace(
            request.directory,
            request.cacheDirectory,
            request.stopAfterFirstFailure,
            { collected },
            request.output,
        )
        val present = EngineBuildSystems.all().filter { it.isPresent(workspace) }
        val changes = collect(request, present)
        collected = changes.toBuildChanges()
        val graph =
            ModuleGraph(present.flatMap { system -> system.modules(workspace).map { ModuleGraph.Node(it, system) } })
        val plans = verificationPlans(
            graph,
            changes,
            changes.files.associateWith(graph::nodesFor),
            request.testDependents,
        )
        val blocker = when {
            !changes.gitUsable -> EngineBlocker.NOT_A_GIT_REPOSITORY
            changes.baseUnresolved || changes.resolvedBranch != request.baseBranch ->
                EngineBlocker.NO_COMPARISON_BASE
            ManifestSearch.find(request.directory, IDE_ONLY_MANIFESTS).isNotEmpty() ->
                EngineBlocker.UNSUPPORTED_BUILD_SYSTEM
            present.isEmpty() -> EngineBlocker.NO_BUILD_SYSTEM
            plans.unresolved.isNotEmpty() -> EngineBlocker.UNRESOLVED_CHANGES
            else -> null
        }
        return EnginePlan(
            if (request.checkConsumers) plans.withConsumers else plans.testsOnly,
            graph.systemSummaries(),
            changes.files,
            plans.unresolved,
            blocker,
            collected,
            workspace,
            present,
        )
    }

    suspend fun run(plan: EnginePlan): Boolean {
        val projectRoot = plan.workspace.root?.toPath() ?: return false
        var passed = true
        for (group in plan.plan.groups) {
            val system = plan.present.firstOrNull { it.id == group.systemId }
            val groupPassed = system != null && group.runInPlannedExecutionRoot(projectRoot, onInvalid = {}) {
                (system as? ChangeAwareEngineBuildSystem)
                    ?.runAndWait(plan.workspace, group.root, group.tasks, plan.changes)
                    ?: system.runAndWait(plan.workspace, group.root, group.tasks)
            }
            passed = passed && groupPassed
            if (!groupPassed && plan.workspace.stopAfterFirstFailure) break
        }
        return passed
    }

    private fun collect(request: EngineRequest, present: List<EngineBuildSystem>): ChangeSet {
        val includeAllFiles = present.any { it.capability<AllFileChangesBuildSystem>() != null }
        val analyzer = ChangeAnalyzer(
            request.directory,
            request.baseBranch,
            present.flatMapTo(HashSet()) { it.sourceExtensions }.ifEmpty { ChangeAnalyzer.DEFAULT_EXTENSIONS },
            includeAllFiles,
            sourceFileNames = present.mapNotNull { it.capability<NamedSourceBuildSystem>() }
                .flatMapTo(HashSet()) { it.sourceFileNames },
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
        )
    }
}

private val IDE_ONLY_MANIFESTS = setOf(
    "settings.gradle",
    "settings.gradle.kts",
    "build.gradle",
    "build.gradle.kts",
    "pom.xml",
)
