package com.aspix2k.affected.impact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class DependencySelectorTest {

    private val identity = DependencyMapIdentity(
        schemaVersion = DEPENDENCY_MAP_SCHEMA_VERSION,
        collectorVersion = "1",
        taskKey = "root|:app|testDebugUnitTest",
        runtimeFingerprint = "gradle-9.2.1|jdk-21|kover-0.9.6",
        inputFingerprint = "inputs-1",
    )

    private val alpha = dependency("Alpha", "alpha-1")
    private val beta = dependency("Beta", "beta-1")
    private val alphaTest = testClass("AlphaTest")
    private val betaTest = testClass("BetaTest")

    @Test
    fun `a changed dependency selects only mapped test classes`() {
        val baseline = complete(
            artifacts = listOf(alpha, beta),
            records = listOf(record(alphaTest, alpha), record(betaTest, beta)),
        )
        val current = snapshot(alpha, dependency("Beta", "beta-2"))

        val impact = exact(DependencySelector.select(SelectionRequest(baseline, current)))

        assertEquals(TestSelection.Classes(setOf(betaTest)), impact.selection)
        assertEquals(setOf(beta.id), impact.changedDependencies)
    }

    @Test
    fun `a deleted dependency selects every test class that observed it`() {
        val baseline = complete(
            artifacts = listOf(alpha, beta),
            records = listOf(record(alphaTest, alpha), record(betaTest, alpha, beta)),
        )

        val impact = exact(DependencySelector.select(SelectionRequest(baseline, snapshot(alpha))))

        assertEquals(TestSelection.Classes(setOf(betaTest)), impact.selection)
        assertEquals(setOf(beta.id), impact.changedDependencies)
    }

    @Test
    fun `an added artifact requires the full task`() {
        val baseline = complete(listOf(alpha), listOf(record(alphaTest, alpha)))
        val current = snapshot(alpha, dependency("Added", "added-1"))

        assertFull(
            DependencySelector.select(SelectionRequest(baseline, current)),
            FullModuleReason.ARTIFACT_SET_CHANGED,
        )
    }

    @Test
    fun `duplicate current class resolution selects every mapped test class`() {
        val baseline = complete(
            listOf(alpha),
            listOf(record(alphaTest, alpha), record(betaTest, alpha)),
        )

        val impact = exact(DependencySelector.select(SelectionRequest(baseline, snapshot(alpha, alpha))))

        assertEquals(TestSelection.Classes(setOf(alphaTest, betaTest)), impact.selection)
        assertEquals(setOf(alpha.id), impact.changedDependencies)
    }

    @Test
    fun `duplicate baseline class invalidates the map`() {
        val baseline = complete(listOf(alpha, alpha), listOf(record(alphaTest, alpha)))

        assertFull(
            DependencySelector.select(SelectionRequest(baseline, snapshot(alpha))),
            FullModuleReason.DUPLICATE_CLASS,
        )
    }

    @Test
    fun `ambiguous current class resolution selects every mapped test class`() {
        val baseline = complete(
            listOf(alpha),
            listOf(record(alphaTest, alpha), record(betaTest, alpha)),
        )

        val impact = exact(
            DependencySelector.select(
                SelectionRequest(
                    baseline,
                    snapshot(alpha, ambiguousDependencies = setOf(alpha.id)),
                ),
            ),
        )

        assertEquals(TestSelection.Classes(setOf(alphaTest, betaTest)), impact.selection)
        assertEquals(setOf(alpha.id), impact.changedDependencies)
    }

    @Test
    fun `unchanged complete map proves an empty selection`() {
        val baseline = complete(listOf(alpha), listOf(record(alphaTest, alpha)))

        val impact = exact(DependencySelector.select(SelectionRequest(baseline, snapshot(alpha))))

        assertEquals(TestSelection.ProvenEmpty, impact.selection)
        assertEquals(emptySet(), impact.changedDependencies)
    }

    @Test
    fun `an unmapped unchanged artifact can change without selecting a test`() {
        val baseline = complete(listOf(alpha, beta), listOf(record(alphaTest, alpha)))
        val current = snapshot(alpha, dependency("Beta", "beta-2"))

        val impact = exact(DependencySelector.select(SelectionRequest(baseline, current)))

        assertEquals(TestSelection.ProvenEmpty, impact.selection)
        assertEquals(setOf(beta.id), impact.changedDependencies)
    }

    @Test
    fun `a missing map cannot prove an empty selection`() {
        assertFull(
            DependencySelector.select(SelectionRequest(null, snapshot(alpha))),
            FullModuleReason.MISSING_DEPENDENCY_MAP,
        )
    }

    @Test
    fun `mandatory fallback takes precedence over map validation`() {
        val request = SelectionRequest(
            baseline = null,
            current = snapshot(alpha),
            mandatoryFallback = FullModuleReason.UNSUPPORTED_CHANGE,
        )

        assertFull(DependencySelector.select(request), FullModuleReason.UNSUPPORTED_CHANGE)
    }

    @Test
    fun `schema mismatch invalidates the map`() {
        val baseline = complete(
            artifacts = listOf(alpha),
            records = listOf(record(alphaTest, alpha)),
            mapIdentity = identity.copy(schemaVersion = DEPENDENCY_MAP_SCHEMA_VERSION + 1),
        )

        assertFull(
            DependencySelector.select(SelectionRequest(baseline, snapshot(alpha))),
            FullModuleReason.SCHEMA_MISMATCH,
        )
    }

    @Test
    fun `input identity mismatch invalidates the map`() {
        val baseline = complete(listOf(alpha), listOf(record(alphaTest, alpha)))
        val current = snapshot(alpha, mapIdentity = identity.copy(inputFingerprint = "inputs-2"))

        assertFull(
            DependencySelector.select(SelectionRequest(baseline, current)),
            FullModuleReason.INPUT_MISMATCH,
        )
    }

    @Test
    fun `task identity mismatch invalidates the map`() {
        val baseline = complete(listOf(alpha), listOf(record(alphaTest, alpha)))
        val current = snapshot(alpha, mapIdentity = identity.copy(taskKey = "root|:other|test"))

        assertFull(DependencySelector.select(SelectionRequest(baseline, current)), FullModuleReason.TASK_MISMATCH)
    }

    @Test
    fun `runtime identity mismatch invalidates the map`() {
        val baseline = complete(listOf(alpha), listOf(record(alphaTest, alpha)))
        val current = snapshot(alpha, mapIdentity = identity.copy(runtimeFingerprint = "gradle-9.3|jdk-21"))

        assertFull(DependencySelector.select(SelectionRequest(baseline, current)), FullModuleReason.RUNTIME_MISMATCH)
    }

    @Test
    fun `collector identity mismatch invalidates the map`() {
        val baseline = complete(listOf(alpha), listOf(record(alphaTest, alpha)))
        val current = snapshot(alpha, mapIdentity = identity.copy(collectorVersion = "2"))

        assertFull(DependencySelector.select(SelectionRequest(baseline, current)), FullModuleReason.COLLECTOR_MISMATCH)
    }

    @Test
    fun `an unclassified change cannot prove an empty selection`() {
        val baseline = complete(listOf(alpha), listOf(record(alphaTest, alpha)))

        assertFull(
            DependencySelector.select(
                SelectionRequest(
                    baseline,
                    snapshot(alpha),
                    mandatoryFallback = FullModuleReason.UNCLASSIFIED_CHANGE,
                ),
            ),
            FullModuleReason.UNCLASSIFIED_CHANGE,
        )
    }

    @Test
    fun `a record outside the baseline artifact catalog invalidates the map`() {
        val baseline = complete(listOf(alpha), listOf(record(alphaTest, beta)))

        assertFull(
            DependencySelector.select(SelectionRequest(baseline, snapshot(alpha))),
            FullModuleReason.CORRUPT_DEPENDENCY_MAP,
        )
    }

    @Test
    fun `duplicate test ownership invalidates a complete map`() {
        val baseline = complete(
            artifacts = listOf(alpha),
            records = listOf(record(alphaTest, alpha), record(alphaTest, alpha)),
        )

        assertFull(
            DependencySelector.select(SelectionRequest(baseline, snapshot(alpha))),
            FullModuleReason.DUPLICATE_TEST_CLASS,
        )
    }

    @Test
    fun `partial workers keep the previous complete map`() {
        val previous = complete(listOf(alpha), listOf(record(alphaTest, alpha)))
        val candidate = candidate(
            expectedWorkers = setOf("worker-1", "worker-2"),
            workers = listOf(worker("worker-1", record(betaTest, beta))),
            artifacts = listOf(beta),
        )

        assertSame(previous, DependencyMapPromotion.promote(previous, candidate))
        assertNull(DependencyMapPromotion.promote(null, candidate))
    }

    @Test
    fun `cancelled collection keeps the previous complete map`() {
        val previous = complete(listOf(alpha), listOf(record(alphaTest, alpha)))
        val candidate = candidate(
            expectedWorkers = setOf("worker-1"),
            workers = listOf(worker("worker-1", record(betaTest, beta))),
            artifacts = listOf(beta),
            cancelled = true,
        )

        assertSame(previous, DependencyMapPromotion.promote(previous, candidate))
    }

    @Test
    fun `collector failure keeps the previous complete map`() {
        val previous = complete(listOf(alpha), listOf(record(alphaTest, alpha)))
        val candidate = candidate(
            expectedWorkers = setOf("worker-1"),
            workers = listOf(worker("worker-1", record(betaTest, beta))),
            artifacts = listOf(beta),
            failed = true,
        )

        assertSame(previous, DependencyMapPromotion.promote(previous, candidate))
        assertNull(DependencyMapPromotion.promote(null, candidate))
    }

    @Test
    fun `duplicate ownership across workers keeps the previous complete map`() {
        val previous = complete(listOf(alpha), listOf(record(alphaTest, alpha)))
        val candidate = candidate(
            expectedWorkers = setOf("worker-1", "worker-2"),
            workers = listOf(
                worker("worker-1", record(betaTest, beta)),
                worker("worker-2", record(betaTest, beta)),
            ),
            artifacts = listOf(beta),
        )

        assertSame(previous, DependencyMapPromotion.promote(previous, candidate))
    }

    @Test
    fun `complete workers atomically replace the previous map`() {
        val previous = complete(listOf(alpha), listOf(record(alphaTest, alpha)))
        val candidate = candidate(
            expectedWorkers = setOf("worker-1", "worker-2"),
            workers = listOf(
                worker("worker-1", record(alphaTest, alpha)),
                worker("worker-2", record(betaTest, beta)),
            ),
            artifacts = listOf(alpha, beta),
        )

        val promoted = DependencyMapPromotion.promote(previous, candidate)

        assertEquals("candidate-run", promoted?.completedRunId)
        assertEquals(setOf(alphaTest, betaTest), promoted?.records?.map { it.testClass }?.toSet())
    }

    private fun dependency(name: String, sha256: String) = ClassDependency(
        id = DependencyId("fixture.$name", "file:/classes/"),
        sha256 = sha256,
    )

    private fun testClass(name: String) = TestClassId("fixture.$name")

    private fun record(testClass: TestClassId, vararg dependencies: ClassDependency) =
        TestDependencyRecord(testClass, dependencies.toSet())

    private fun complete(
        artifacts: List<ClassDependency>,
        records: List<TestDependencyRecord>,
        mapIdentity: DependencyMapIdentity = identity,
    ) = CompleteDependencyMap(mapIdentity, artifacts, records, completedRunId = "baseline-run")

    private fun snapshot(
        vararg artifacts: ClassDependency,
        mapIdentity: DependencyMapIdentity = identity,
        ambiguousDependencies: Set<DependencyId> = emptySet(),
    ) = CurrentTaskSnapshot(mapIdentity, artifacts.toList(), ambiguousDependencies)

    private fun candidate(
        expectedWorkers: Set<String>,
        workers: List<WorkerDependencyMap>,
        artifacts: List<ClassDependency>,
        cancelled: Boolean = false,
        failed: Boolean = false,
    ) = DependencyMapCandidate(
        identity = identity,
        artifacts = artifacts,
        expectedWorkers = expectedWorkers,
        workers = workers,
        completedRunId = "candidate-run",
        cancelled = cancelled,
        failed = failed,
    )

    private fun worker(id: String, vararg records: TestDependencyRecord) = WorkerDependencyMap(id, records.toList())

    private fun exact(impact: TestImpact): TestImpact.Exact = assertIs(impact)

    private fun assertFull(impact: TestImpact, reason: FullModuleReason) {
        assertEquals(reason, assertIs<TestImpact.FullModule>(impact).reason)
    }
}
