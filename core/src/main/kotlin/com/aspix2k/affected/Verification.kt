package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.BuildSystems
import com.aspix2k.affected.build.ChangeAwareSuspendingBuildSystem
import com.aspix2k.affected.build.SuspendingBuildSystem
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

object Verification {

    data class Outcome(val plan: Plan, val passed: Boolean)

    class Prepared internal constructor(
        val plan: Plan,
        internal val changes: BuildChanges,
    )

    data class PreparedPlans(
        val testsOnly: Prepared,
        val withConsumers: Prepared,
    ) {
        fun select(checkConsumers: Boolean): Prepared = if (checkConsumers) withConsumers else testsOnly
    }

    suspend fun prepare(project: Project): Prepared {
        val changes = ProjectChanges.collectSuspending(project)
        return prepare(project, changes)
    }

    suspend fun prepare(project: Project, changes: ProjectChanges.Result): Prepared =
        withContext(Dispatchers.Default) {
            prepare(ModuleGraph.create(project), changes)
                .select(AffectedSettings.getInstance().checkConsumers)
        }

    suspend fun plan(project: Project): Plan {
        return prepare(project).plan
    }

    suspend fun plan(project: Project, changes: ProjectChanges.Result): Plan = withContext(Dispatchers.Default) {
        val graph = ModuleGraph.create(project)
        verificationPlan(graph, changes, AffectedSettings.getInstance().checkConsumers)
    }

    internal fun prepare(
        graph: ModuleGraph,
        changes: ProjectChanges.Result,
        owners: Map<java.io.File, List<ModuleGraph.Node>> = changes.files.associateWith(graph::nodesFor),
    ): PreparedPlans {
        val buildChanges = changes.toBuildChanges()
        val plans = verificationPlans(graph, changes, owners)
        return PreparedPlans(
            testsOnly = Prepared(plans.testsOnly, buildChanges),
            withConsumers = Prepared(plans.withConsumers, buildChanges),
        )
    }

    suspend fun runAndWait(project: Project, plan: Plan): Outcome {
        return runAndWait(
            project,
            Prepared(plan, BuildChanges(emptyList(), emptySet(), comparedToBase = false)),
        )
    }

    suspend fun runAndWait(project: Project, prepared: Prepared): Outcome {
        val plan = prepared.plan
        if (plan.isEmpty) return Outcome(plan, passed = true)
        val claim = project.service<AffectedState>().tryClaimVerification()
            ?: return Outcome(plan, passed = false)
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
            if (plan.isEmpty) return Outcome(plan, passed = true)
            if (!claim.markRunning()) return Outcome(plan, passed = false)
            passed = coroutineScope {
                plan.groups.map { group ->
                    async(Dispatchers.Default) {
                        when (val system = BuildSystems.byId(group.systemId)) {
                            null -> true
                            is ChangeAwareSuspendingBuildSystem ->
                                system.runAndWaitSuspending(project, group.root, group.tasks, prepared.changes)
                            is SuspendingBuildSystem ->
                                system.runAndWaitSuspending(project, group.root, group.tasks)
                            else -> withContext(Dispatchers.IO) {
                                system.runAndWait(project, group.root, group.tasks)
                            }
                        }
                    }
                }.awaitAll().all { it }
            }
            return Outcome(plan, passed)
        } finally {
            claim.close()
        }
    }
}

internal fun verificationPlan(
    graph: ModuleGraph,
    changes: ProjectChanges.Result,
    checkConsumers: Boolean,
): Plan = verificationPlans(graph, changes).select(checkConsumers)

private data class VerificationPlans(
    val testsOnly: Plan,
    val withConsumers: Plan,
) {
    fun select(checkConsumers: Boolean): Plan = if (checkConsumers) withConsumers else testsOnly
}

private fun verificationPlans(
    graph: ModuleGraph,
    changes: ProjectChanges.Result,
    owners: Map<java.io.File, List<ModuleGraph.Node>> = changes.files.associateWith(graph::nodesFor),
): VerificationPlans {
    if (changes.files.isEmpty()) {
        val empty = Plan(emptyList(), 0, 0)
        return VerificationPlans(empty, empty)
    }
    val effectiveOwners = graph.ownersForChanges(changes.toBuildChanges(), owners)
    val changed = effectiveOwners.values.flatten().distinct()
    val testConsumers = graph.transitiveTestConsumers(changed.toSet())
    val apiNodes = effectiveOwners.flatMapTo(HashSet()) { (file, nodes) ->
        nodes.filter { node ->
            affectsConsumers(
                systemId = node.system.id,
                path = file.invariantSeparatorsPath,
                signatureTouched = file in changes.apiTouched,
            )
        }
    }
    val tested = (changed + testConsumers).map { it.info() }
    val testsOnly = TaskPlanner.plan(tested, emptyList())
    val consumers = if (apiNodes.isEmpty()) emptyList() else graph.directDependents(apiNodes)
    return VerificationPlans(
        testsOnly = testsOnly,
        withConsumers = if (consumers.isEmpty()) {
            testsOnly
        } else {
            TaskPlanner.plan(tested, consumers.map { it.info() })
        },
    )
}

internal fun ProjectChanges.Result.toBuildChanges(): BuildChanges = BuildChanges(
    files = files.map { it.absoluteFile.normalize().invariantSeparatorsPath },
    exactSelectionEligible = exactSelectionEligible
        .mapTo(HashSet()) { it.absoluteFile.normalize().invariantSeparatorsPath },
    comparedToBase = comparedToBase,
)

internal fun affectsConsumers(systemId: String, path: String, signatureTouched: Boolean): Boolean =
    signatureTouched || systemId !in JVM_BUILD_SYSTEMS && !ChangeAnalyzer.isTestSource(systemId, path)

private val JVM_BUILD_SYSTEMS = setOf("GRADLE", "MAVEN")
