package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.BuildSystem
import com.aspix2k.affected.build.BuildSystems
import com.aspix2k.affected.build.ChangeAwareSuspendingBuildSystem
import com.aspix2k.affected.build.SuspendingBuildSystem
import com.aspix2k.affected.build.gradle.isAndroidInstrumentationSource
import com.aspix2k.affected.build.gradle.selectAndroidTestTask
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Verification {

    enum class Blocker { UNRESOLVED_CHANGES, NO_COMPARISON_BASE, NOT_STARTED }

    data class Outcome(val plan: Plan, val passed: Boolean, val blocker: Blocker? = null)

    class Prepared internal constructor(
        val plan: Plan,
        internal val changes: BuildChanges,
        val unresolvedFiles: Int = 0,
        val baseUnresolved: Boolean = false,
        val uncovered: List<File> = emptyList(),
        val unresolved: List<File> = emptyList(),
    ) {
        val changedFiles: Int get() = changes.files.size
    }

    data class PreparedPlans(
        val testsOnly: Prepared,
        val withConsumers: Prepared,
    ) {
        fun select(checkConsumers: Boolean): Prepared = if (checkConsumers) withConsumers else testsOnly
    }

    suspend fun prepare(project: Project, guard: Boolean = false): Prepared {
        val changes = ProjectChanges.collectSuspending(project)
        return withContext(Dispatchers.Default) {
            val settings = AffectedSettings.getInstance()
            val testDependents = settings.testDependents || guard && settings.guardsTestDependents
            prepare(ModuleGraph.create(project), changes, testDependents = testDependents)
                .select(settings.checkConsumers)
        }
    }

    internal fun prepare(
        graph: ModuleGraph,
        changes: ProjectChanges.Result,
        owners: Map<File, List<ModuleGraph.Node>> = changes.files.associateWith(graph::nodesFor),
        testDependents: Boolean = false,
    ): PreparedPlans {
        val buildChanges = changes.toBuildChanges()
        val plans = verificationPlans(graph, changes, owners, testDependents)
        val prepared = { plan: Plan ->
            Prepared(
                plan,
                buildChanges,
                plans.unresolved.size,
                changes.baseUnresolved,
                changes.uncovered,
                plans.unresolved,
            )
        }
        return PreparedPlans(prepared(plans.testsOnly), prepared(plans.withConsumers))
    }

    suspend fun runAndWait(project: Project, prepared: Prepared): Outcome {
        val plan = prepared.plan
        if (plan.isEmpty) return withoutWork(prepared)
        val claim = project.service<AffectedState>().tryClaimVerification()
            ?: return Outcome(plan, passed = false, Blocker.NOT_STARTED)
        return runClaimedAndWait(project, prepared, claim)
    }

    suspend fun runClaimedAndWait(
        project: Project,
        prepared: Prepared,
        claim: AffectedRunClaim,
    ): Outcome {
        val plan = prepared.plan
        var passed = false
        try {
            if (plan.isEmpty) return withoutWork(prepared)
            if (!claim.markRunning()) return Outcome(plan, passed = false, Blocker.NOT_STARTED)
            val stopAfterFirstFailure = AffectedSettings.getInstance().stopAfterFirstFailure
            passed = runClaimedGroups(
                project,
                claim,
                plan.groups,
                Dispatchers.Default,
                stopAfterFirstFailure,
            ) { group ->
                group.runInPlannedExecutionRoot(project) {
                    when (val system = BuildSystems.byId(group.systemId)) {
                        null -> false
                        is ChangeAwareSuspendingBuildSystem ->
                            system.runAndWaitSuspending(project, group.root, group.tasks, prepared.changes)
                        is SuspendingBuildSystem ->
                            system.runAndWaitSuspending(project, group.root, group.tasks)
                        else -> withContext(Dispatchers.IO) {
                            system.runAndWait(project, group.root, group.tasks)
                        }
                    }
                }
            }
            return completedOutcome(plan, passed, prepared.unresolvedFiles, prepared.baseUnresolved)
        } finally {
            claim.close()
        }
    }

    private fun withoutWork(prepared: Prepared): Outcome = when {
        prepared.baseUnresolved -> Outcome(prepared.plan, passed = false, Blocker.NO_COMPARISON_BASE)
        verificationPassesWithoutWork(prepared) -> Outcome(prepared.plan, passed = true)
        else -> Outcome(prepared.plan, passed = false, Blocker.UNRESOLVED_CHANGES)
    }
}

internal fun completedOutcome(
    plan: Plan,
    passed: Boolean,
    unresolvedFiles: Int,
    baseUnresolved: Boolean = false,
): Verification.Outcome = when {
    passed && unresolvedFiles > 0 -> Verification.Outcome(plan, passed = false, Verification.Blocker.UNRESOLVED_CHANGES)
    passed && baseUnresolved -> Verification.Outcome(plan, passed = false, Verification.Blocker.NO_COMPARISON_BASE)
    else -> Verification.Outcome(plan, passed)
}

internal fun verificationPassesWithoutWork(prepared: Verification.Prepared): Boolean =
    prepared.plan.isEmpty && prepared.unresolvedFiles == 0

fun <T> runWithRequiredAdapter(
    adapter: T?,
    run: (T) -> Boolean,
): Boolean {
    return adapter != null && run(adapter)
}

private data class VerificationPlans(
    val testsOnly: Plan,
    val withConsumers: Plan,
    val unresolved: List<File> = emptyList(),
)

private fun verificationPlans(
    graph: ModuleGraph,
    changes: ProjectChanges.Result,
    owners: Map<File, List<ModuleGraph.Node>>,
    testDependents: Boolean,
): VerificationPlans {
    if (changes.files.isEmpty()) {
        val empty = Plan(emptyList(), 0, 0)
        return VerificationPlans(empty, empty)
    }
    val effectiveOwners = graph.ownersForChanges(changes.toBuildChanges(), owners)
    val changed = effectiveOwners.values.flatten().distinct()
    val testConsumers = graph.transitiveTestConsumers(changed.toSet()) + if (testDependents) {
        val production = effectiveOwners.flatMapTo(HashSet()) { (file, nodes) ->
            nodes.filterNot { it.system.isTestSource(it.pathInBuildRoot(file)) }
        }
        graph.transitiveTestConsumers(production, everySystem = true)
    } else {
        emptyList()
    }
    val apiNodes = effectiveOwners.flatMapTo(HashSet()) { (file, nodes) ->
        nodes.filter { node ->
            affectsConsumers(
                system = node.system,
                path = node.pathInBuildRoot(file),
                signatureTouched = file in changes.apiTouched,
            )
        }
    }
    val changedNodes = changed.toSet()
    val tested = (changed + testConsumers).distinct().map { node ->
        androidTestInfo(node, if (node in changedNodes) pathsOwnedBy(node, effectiveOwners) else emptyList())
    }
    val testsOnly = TaskPlanner.plan(tested, emptyList())
    val consumers = if (apiNodes.isEmpty()) emptyList() else graph.directDependents(apiNodes)
    val verifiedByConsumers = changed.filterTo(HashSet()) { node ->
        !node.isVerifiable() && graph.transitiveTestConsumers(setOf(node), testDependents).any { it.isVerifiable() }
    }
    return VerificationPlans(
        testsOnly = testsOnly,
        withConsumers = if (consumers.isEmpty()) {
            testsOnly
        } else {
            TaskPlanner.plan(tested, consumers.map { it.info() })
        },
        unresolved = changes.files.filter { file ->
            file.extension.lowercase() in graph.sourceExtensions &&
                effectiveOwners[file].orEmpty().none { it.isVerifiable() || it in verifiedByConsumers }
        },
    )
}

private fun ModuleGraph.Node.isVerifiable(): Boolean = module.hasTests || module.compileTask != null

internal fun ProjectChanges.Result.toBuildChanges(): BuildChanges = BuildChanges(
    files = files.map { it.absoluteFile.normalize().invariantSeparatorsPath },
    exactSelectionEligible = exactSelectionEligible
        .mapTo(HashSet()) { it.absoluteFile.normalize().invariantSeparatorsPath },
    comparedToBase = comparedToBase,
)

internal fun affectsConsumers(system: BuildSystem, path: String, signatureTouched: Boolean): Boolean =
    signatureTouched || !system.consumersNeedSignatureChange && !system.isTestSource(path)

private fun ModuleGraph.Node.pathInBuildRoot(file: File): String =
    file.invariantSeparatorsPath.removePrefix("${buildRoot.trimEnd('/')}/")

private fun pathsOwnedBy(
    node: ModuleGraph.Node,
    owners: Map<File, List<ModuleGraph.Node>>,
): List<String> = owners.mapNotNull { (file, nodes) ->
    file.invariantSeparatorsPath.takeIf { node in nodes }
}

private fun androidTestInfo(node: ModuleGraph.Node, changedPaths: List<String>): ModuleInfo {
    val info = node.info()
    if (info.systemId != "GRADLE" || changedPaths.isEmpty()) return info
    return info.copy(
        testTask = selectAndroidTestTask(
            info.testTask,
            node.module.extraTasks,
            changedPaths.all(::isAndroidInstrumentationSource),
        ),
    )
}
