package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import com.intellij.openapi.project.Project

internal fun verificationPlan(
    graph: ModuleGraph,
    changes: ChangeSet,
    checkConsumers: Boolean,
): Plan = Verification.prepare(graph, changes).select(checkConsumers).plan

internal suspend fun Verification.runAndWait(project: Project, plan: Plan): Verification.Outcome =
    runAndWait(project, Verification.Prepared(plan, BuildChanges(emptyList(), emptySet(), comparedToBase = false)))
