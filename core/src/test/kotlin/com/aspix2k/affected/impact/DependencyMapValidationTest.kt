package com.aspix2k.affected.impact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class DependencyMapValidationTest : DependencyMapFixture() {

    @Test
    fun `an identity rejects a nonpositive schema version`() {
        listOf(0, -1).forEach { schema ->
            assertFailsWith<IllegalArgumentException> { identity.copy(schemaVersion = schema) }
        }
        assertEquals(1, identity.copy(schemaVersion = 1).schemaVersion)
    }

    @Test
    fun `an identity rejects every blank field`() {
        listOf("", " ").forEach { blank ->
            assertFailsWith<IllegalArgumentException> { identity.copy(collectorVersion = blank) }
            assertFailsWith<IllegalArgumentException> { identity.copy(taskKey = blank) }
            assertFailsWith<IllegalArgumentException> { identity.copy(runtimeFingerprint = blank) }
            assertFailsWith<IllegalArgumentException> { identity.copy(inputFingerprint = blank) }
        }
    }

    @Test
    fun `dependency values reject blank parts`() {
        listOf("", " ").forEach { blank ->
            assertFailsWith<IllegalArgumentException> { TestClassId(blank) }
            assertFailsWith<IllegalArgumentException> { DependencyId(blank, "file:/classes/") }
            assertFailsWith<IllegalArgumentException> { DependencyId("fixture.Alpha", blank) }
            assertFailsWith<IllegalArgumentException> { ClassDependency(alpha.id, blank) }
        }
    }

    @Test
    fun `selection ids expose their classes and reject an empty set`() {
        assertEquals(setOf(alphaTest), TestSelection.Classes(setOf(alphaTest)).ids)
        assertFailsWith<IllegalArgumentException> { TestSelection.Classes(emptySet()) }
    }

    @Test
    fun `current schema mismatch invalidates the map`() {
        val baseline = complete(listOf(alpha), listOf(record(alphaTest, alpha)))
        val current = CurrentTaskSnapshot(
            identity.copy(schemaVersion = DEPENDENCY_MAP_SCHEMA_VERSION + 1),
            listOf(alpha),
        )

        assertFull(DependencySelector.select(SelectionRequest(baseline, current)), FullModuleReason.SCHEMA_MISMATCH)
    }

    @Test
    fun `a map without records cannot prove an exact selection`() {
        assertFull(
            DependencySelector.select(SelectionRequest(complete(listOf(alpha), emptyList()), snapshot(alpha))),
            FullModuleReason.CORRUPT_DEPENDENCY_MAP,
        )
    }

    @Test
    fun `any record with a mismatching dependency invalidates the map`() {
        val staleBeta = dependency("Beta", "beta-stale")
        listOf(
            listOf(record(alphaTest, alpha), record(betaTest, staleBeta)),
            listOf(record(alphaTest, staleBeta), record(betaTest, beta)),
            listOf(record(alphaTest, alpha, staleBeta), record(betaTest, beta)),
            listOf(record(alphaTest, gamma, staleBeta), record(betaTest, beta)),
        ).forEach { records ->
            val baseline = complete(listOf(alpha, beta, gamma), records)

            assertFull(
                DependencySelector.select(SelectionRequest(baseline, snapshot(alpha, beta, gamma))),
                FullModuleReason.CORRUPT_DEPENDENCY_MAP,
            )
        }
    }

    @Test
    fun `a duplicated baseline class invalidates the map wherever it sits`() {
        listOf(listOf(alpha, beta, beta), listOf(alpha, alpha, beta), listOf(beta, alpha, beta)).forEach { artifacts ->
            val baseline = complete(artifacts, listOf(record(alphaTest, alpha)))

            assertFull(
                DependencySelector.select(SelectionRequest(baseline, snapshot(alpha, beta))),
                FullModuleReason.DUPLICATE_CLASS,
            )
        }
    }

    @Test
    fun `an unknown current class or ambiguous dependency requires the full task`() {
        val baseline = complete(listOf(alpha, beta), listOf(record(alphaTest, alpha), record(betaTest, beta)))
        val added = dependency("Added", "added-1")
        listOf(
            snapshot(alpha, beta, added),
            snapshot(added, alpha, beta),
            snapshot(alpha, beta, ambiguousDependencies = setOf(added.id)),
            snapshot(alpha, beta, ambiguousDependencies = setOf(alpha.id, added.id)),
        ).forEach { current ->
            assertFull(
                DependencySelector.select(SelectionRequest(baseline, current)),
                FullModuleReason.ARTIFACT_SET_CHANGED,
            )
        }
    }

    @Test
    fun `a record is selected when any of its dependencies changed`() {
        val baseline = complete(
            artifacts = listOf(alpha, beta, gamma),
            records = listOf(
                record(alphaTest, alpha, beta),
                record(betaTest, alpha),
                record(testClass("GammaTest"), gamma, alpha),
                record(testClass("EmptyTest")),
            ),
        )
        val current = snapshot(alpha, dependency("Beta", "beta-2"), gamma)

        val impact = exact(DependencySelector.select(SelectionRequest(baseline, current)))

        assertEquals(TestSelection.Classes(setOf(alphaTest)), impact.selection)
        assertEquals(setOf(beta.id), impact.changedDependencies)
    }

    @Test
    fun `unchanged artifacts around a changed one stay unchanged`() {
        val baseline = complete(
            artifacts = listOf(alpha, beta, gamma),
            records = listOf(record(alphaTest, alpha), record(betaTest, beta), record(testClass("GammaTest"), gamma)),
        )
        val current = snapshot(alpha, beta, dependency("Gamma", "gamma-2"))

        val impact = exact(DependencySelector.select(SelectionRequest(baseline, current)))

        assertEquals(TestSelection.Classes(setOf(testClass("GammaTest"))), impact.selection)
        assertEquals(setOf(gamma.id), impact.changedDependencies)
    }
}
