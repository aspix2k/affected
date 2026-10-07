package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.BuildSystems
import com.aspix2k.affected.build.ChangeAwareSuspendingBuildSystem
import com.aspix2k.affected.build.SuspendingBuildSystem
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.TimeSource

object Verification {

    enum class Blocker { UNRESOLVED_CHANGES, NO_COMPARISON_BASE, NOT_STARTED }

    data class Outcome(
        val plan: Plan,
        val passed: Boolean,
        val blocker: Blocker? = null,
        val summary: RunSummary? = null,
    )

    class Prepared internal constructor(
        val plan: Plan,
        internal val changes: BuildChanges,
        val unresolvedFiles: Int = 0,
        val baseUnresolved: Boolean = false,
        val uncovered: List<File> = emptyList(),
        val unresolved: List<File> = emptyList(),
        val inventory: TestInventory = TestInventory(),
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
        changes: ChangeSet,
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
                graph.testInventory(),
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
            val state = project.service<AffectedState>()
            state.lastVerification = null
            val stopAfterFirstFailure = AffectedSettings.getInstance().stopAfterFirstFailure
            val started = TimeSource.Monotonic.markNow()
            val durations = ConcurrentLinkedQueue<RecordedDuration>()
            val results = ConcurrentLinkedQueue<GroupResult>()
            passed = runClaimedGroups(
                project,
                claim,
                plan.groups,
                Dispatchers.Default,
                stopAfterFirstFailure,
            ) { group ->
                val groupStarted = TimeSource.Monotonic.markNow()
                group.runInPlannedExecutionRoot(project) {
                    runBuildTasks(project, group.systemId, group.root, group.tasks, prepared.changes)
                }.also { groupPassed ->
                    if (groupPassed) {
                        durations += group.recordedDuration(
                            groupStarted.elapsedNow().inWholeMilliseconds,
                            System.currentTimeMillis(),
                        )
                    }
                    results.recordGroup(claim, group, groupPassed)
                }
            }
            state.lastVerification = VerificationRecord(
                plan.groups.mapNotNull { group -> results.firstOrNull { it.group == group } },
                prepared.changes,
            )
            val outcome = completedOutcome(plan, passed, prepared.unresolvedFiles, prepared.baseUnresolved)
            return if (outcome.passed) {
                outcome.copy(
                    summary = summarize(project, prepared, started.elapsedNow().inWholeMilliseconds, durations),
                )
            } else {
                outcome
            }
        } finally {
            claim.close()
        }
    }

    private suspend fun summarize(
        project: Project,
        prepared: Prepared,
        durationMillis: Long,
        durations: Collection<RecordedDuration>,
    ): RunSummary = withContext(Dispatchers.IO) {
        val recorded = DurationStore.forProject(project).record(durations.toList())
        summarizeRun(prepared.plan, prepared.inventory, durationMillis, recorded)
    }

    private fun withoutWork(prepared: Prepared): Outcome = when {
        prepared.baseUnresolved -> Outcome(prepared.plan, passed = false, Blocker.NO_COMPARISON_BASE)
        verificationPassesWithoutWork(prepared) -> Outcome(prepared.plan, passed = true)
        else -> Outcome(prepared.plan, passed = false, Blocker.UNRESOLVED_CHANGES)
    }
}

internal fun MutableCollection<GroupResult>.recordGroup(claim: AffectedRunClaim, group: TaskGroup, passed: Boolean) {
    if (passed || !claim.isTerminationRequested()) add(GroupResult(group, passed))
}

internal suspend fun runBuildTasks(
    project: Project,
    systemId: String,
    root: String,
    tasks: List<String>,
    changes: BuildChanges,
): Boolean = when (val system = BuildSystems.byId(systemId)) {
    null -> false
    is ChangeAwareSuspendingBuildSystem -> system.runAndWaitSuspending(project, root, tasks, changes)
    is SuspendingBuildSystem -> system.runAndWaitSuspending(project, root, tasks)
    else -> withContext(Dispatchers.IO) { system.runAndWait(project, root, tasks) }
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
