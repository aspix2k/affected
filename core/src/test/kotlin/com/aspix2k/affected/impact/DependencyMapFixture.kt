package com.aspix2k.affected.impact

import com.aspix2k.affected.build.cmake.sha256
import kotlin.test.assertEquals
import kotlin.test.assertIs

internal abstract class DependencyMapFixture {

    protected val identity = DependencyMapIdentity(
        schemaVersion = DEPENDENCY_MAP_SCHEMA_VERSION,
        collectorVersion = "1",
        taskKey = "root|:app|testDebugUnitTest",
        runtimeFingerprint = "gradle-9.2.1|jdk-21|kover-0.9.6",
        inputFingerprint = "inputs-1",
    )

    protected val alpha = dependency("Alpha", "alpha-1")
    protected val beta = dependency("Beta", "beta-1")
    protected val gamma = dependency("Gamma", "gamma-1")
    protected val alphaTest = testClass("AlphaTest")
    protected val betaTest = testClass("BetaTest")

    protected fun dependency(name: String, hashSeed: String) = ClassDependency(
        id = DependencyId("fixture.$name", "file:/classes/"),
        sha256 = sha256(hashSeed),
    )

    protected fun testClass(name: String) = TestClassId("fixture.$name")

    protected fun record(testClass: TestClassId, vararg dependencies: ClassDependency) =
        TestDependencyRecord(testClass, dependencies.toSet())

    protected fun complete(
        artifacts: List<ClassDependency>,
        records: List<TestDependencyRecord>,
        completedRunId: String = "baseline-run",
    ) = CompleteDependencyMap(identity, artifacts, records, completedRunId)

    protected fun snapshot(
        vararg artifacts: ClassDependency,
        ambiguousDependencies: Set<DependencyId> = emptySet(),
    ) = CurrentTaskSnapshot(identity, artifacts.toList(), ambiguousDependencies)

    protected fun candidate(
        expectedWorkers: Set<String>,
        workers: List<WorkerDependencyMap>,
        artifacts: List<ClassDependency>,
        expectedTestClasses: Set<TestClassId> = workers
            .flatMap(WorkerDependencyMap::records)
            .mapTo(LinkedHashSet(), TestDependencyRecord::testClass),
        completedRunId: String = "candidate-run",
        mapIdentity: DependencyMapIdentity = identity,
    ) = DependencyMapCandidate(
        identity = mapIdentity,
        artifacts = artifacts,
        expectedWorkers = expectedWorkers,
        expectedTestClasses = expectedTestClasses,
        collectsAllTests = true,
        workers = workers,
        completedRunId = completedRunId,
        cancelled = false,
        failed = false,
    )

    protected fun worker(id: String, vararg records: TestDependencyRecord) =
        WorkerDependencyMap(id, records.toList())

    protected fun exact(impact: TestImpact): TestImpact.Exact = assertIs(impact)

    protected fun assertFull(impact: TestImpact, reason: FullModuleReason) {
        assertEquals(reason, assertIs<TestImpact.FullModule>(impact).reason)
    }
}
