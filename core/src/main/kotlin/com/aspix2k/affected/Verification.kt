package com.aspix2k.affected

import com.aspix2k.affected.build.BuildSystems
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * The verification itself, without an action or a dialog around it: what to run
 * for the current changes, and how it ended.
 */
object Verification {

    data class Outcome(val plan: Plan, val passed: Boolean)

    fun plan(project: Project): Plan {
        val changes = ProjectChanges.collect(project)
        if (changes.files.isEmpty()) return Plan(emptyList(), 0, 0)

        return ApplicationManager.getApplication().runReadAction<Plan> {
            val graph = ModuleGraph(project)

            val changed = changes.files.mapNotNull { graph.nodeFor(it) }.distinct()
            val apiNodes = changes.apiTouched.mapNotNull { graph.nodeFor(it) }.toSet()
            val consumers = when {
                !AffectedSettings.getInstance().checkConsumers -> emptyList()
                apiNodes.isEmpty() -> emptyList()
                else -> graph.directDependents(apiNodes)
            }

            TaskPlanner.plan(changed.map { it.info() }, consumers.map { it.info() })
        }
    }

    /** Runs the plan and waits, so a caller can decide whether to let an operation through. */
    fun runAndWait(project: Project, plan: Plan): Outcome {
        if (plan.isEmpty) return Outcome(plan, passed = true)

        val passed = plan.groups.all { group ->
            BuildSystems.byId(group.systemId)?.runAndWait(project, group.root, group.tasks) ?: true
        }
        return Outcome(plan, passed)
    }
}
