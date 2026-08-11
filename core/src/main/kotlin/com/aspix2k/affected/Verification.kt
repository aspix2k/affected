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

    suspend fun prepare(project: Project): Prepared {
        val changes = ProjectChanges.collectSuspending(project)
        return prepare(project, changes)
    }

    suspend fun prepare(project: Project, changes: ProjectChanges.Result): Prepared =
        withContext(Dispatchers.Default) {
            val buildChanges = BuildChanges(
                files = changes.files.map { it.absoluteFile.normalize().invariantSeparatorsPath },
                exactSelectionEligible = changes.exactSelectionEligible
                    .mapTo(HashSet()) { it.absoluteFile.normalize().invariantSeparatorsPath },
                comparedToBase = changes.comparedToBase,
            )
            Prepared(plan(project, changes), buildChanges)
        }

    suspend fun plan(project: Project): Plan {
        return prepare(project).plan
    }

    suspend fun plan(project: Project, changes: ProjectChanges.Result): Plan = withContext(Dispatchers.Default) {
        if (changes.files.isEmpty()) return@withContext Plan(emptyList(), 0, 0)

        val graph = ModuleGraph.create(project)
        val owners = changes.files.associateWith(graph::nodesFor)
        val changed = owners.values.flatten().distinct()
        val apiNodes = owners.flatMapTo(HashSet()) { (file, nodes) ->
            nodes.filter { node ->
                affectsConsumers(
                    systemId = node.system.id,
                    path = file.invariantSeparatorsPath,
                    signatureTouched = file in changes.apiTouched,
                )
            }
        }
        val consumers = when {
            !AffectedSettings.getInstance().checkConsumers -> emptyList()
            apiNodes.isEmpty() -> emptyList()
            else -> graph.directDependents(apiNodes)
        }

        TaskPlanner.plan(changed.map { it.info() }, consumers.map { it.info() })
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

        val state = project.service<AffectedState>()
        state.markRunning()
        var passed = false
        try {
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
            state.markFinished()
        }
    }
}

internal fun affectsConsumers(systemId: String, path: String, signatureTouched: Boolean): Boolean =
    signatureTouched || systemId !in JVM_BUILD_SYSTEMS && !ChangeAnalyzer.isTestSource(systemId, path)

private val JVM_BUILD_SYSTEMS = setOf("GRADLE", "MAVEN")
