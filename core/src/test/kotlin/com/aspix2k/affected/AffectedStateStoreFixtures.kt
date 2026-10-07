package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges

internal fun AffectedStateStore.complete(expectedRevision: Long, modules: List<AffectedModule>): Boolean =
    complete(expectedRevision, AffectedAnalysis(modules, EMPTY_CHANGES, EMPTY_PLANS))

private val EMPTY_CHANGES = ChangeSet(emptyList(), emptySet(), emptySet(), comparedToBase = false)

private val EMPTY_PLANS = Verification.PreparedPlans(
    testsOnly = Verification.Prepared(
        Plan(emptyList(), 0, 0),
        BuildChanges(emptyList(), emptySet(), comparedToBase = false),
    ),
    withConsumers = Verification.Prepared(
        Plan(emptyList(), 0, 0),
        BuildChanges(emptyList(), emptySet(), comparedToBase = false),
    ),
)
