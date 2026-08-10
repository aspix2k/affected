package com.aspix2k.affected.impact

data class DependencyMapIdentity(
    val schemaVersion: Int,
    val collectorVersion: String,
    val taskKey: String,
    val runtimeFingerprint: String,
    val inputFingerprint: String,
) {
    init {
        require(schemaVersion > 0)
        require(collectorVersion.isNotBlank())
        require(taskKey.isNotBlank())
        require(runtimeFingerprint.isNotBlank())
        require(inputFingerprint.isNotBlank())
    }
}

data class CompleteDependencyMap(
    val identity: DependencyMapIdentity,
    val artifacts: List<ClassDependency>,
    val records: List<TestDependencyRecord>,
    val completedRunId: String,
)

data class CurrentTaskSnapshot(
    val identity: DependencyMapIdentity,
    val artifacts: List<ClassDependency>,
    val ambiguousDependencies: Set<DependencyId> = emptySet(),
)

data class SelectionRequest(
    val baseline: CompleteDependencyMap?,
    val current: CurrentTaskSnapshot,
    val mandatoryFallback: FullModuleReason? = null,
)

object DependencySelector {

    fun select(request: SelectionRequest): TestImpact {
        request.mandatoryFallback?.let { return TestImpact.FullModule(it) }
        val baseline = request.baseline
            ?: return TestImpact.FullModule(FullModuleReason.MISSING_DEPENDENCY_MAP)
        if (baseline.completedRunId.isBlank()) {
            return TestImpact.FullModule(FullModuleReason.CORRUPT_DEPENDENCY_MAP)
        }
        identityMismatch(baseline.identity, request.current.identity)?.let {
            return TestImpact.FullModule(it)
        }

        val baselineArtifacts = baseline.artifacts.uniqueById()
            ?: return TestImpact.FullModule(FullModuleReason.DUPLICATE_CLASS)
        val currentArtifacts = request.current.artifacts.groupBy(ClassDependency::id)
        if (baseline.records.hasDuplicateTests()) {
            return TestImpact.FullModule(FullModuleReason.DUPLICATE_TEST_CLASS)
        }
        if (!baseline.records.match(baselineArtifacts)) {
            return TestImpact.FullModule(FullModuleReason.CORRUPT_DEPENDENCY_MAP)
        }
        if (
            currentArtifacts.keys.any { it !in baselineArtifacts } ||
            request.current.ambiguousDependencies.any { it !in baselineArtifacts }
        ) {
            return TestImpact.FullModule(FullModuleReason.ARTIFACT_SET_CHANGED)
        }

        val changed = baselineArtifacts.mapNotNullTo(LinkedHashSet()) { (id, previous) ->
            val current = currentArtifacts[id]
            id.takeIf {
                id in request.current.ambiguousDependencies ||
                    current == null ||
                    current.size != 1 ||
                    current.single().sha256 != previous.sha256
            }
        }
        val selected = baseline.records
            .filter { record -> record.dependencies.any { it.id in changed } }
            .mapTo(LinkedHashSet(), TestDependencyRecord::testClass)
        val selection = if (selected.isEmpty()) TestSelection.ProvenEmpty else TestSelection.Classes(selected)

        return TestImpact.Exact(selection, changed)
    }
}

data class WorkerDependencyMap(
    val workerId: String,
    val records: List<TestDependencyRecord>,
)

data class DependencyMapCandidate(
    val identity: DependencyMapIdentity,
    val artifacts: List<ClassDependency>,
    val expectedWorkers: Set<String>,
    val expectedTestClasses: Set<TestClassId>,
    val collectsAllTests: Boolean,
    val workers: List<WorkerDependencyMap>,
    val completedRunId: String,
    val cancelled: Boolean,
    val failed: Boolean,
)

object DependencyMapPromotion {

    fun promote(
        previous: CompleteDependencyMap?,
        candidate: DependencyMapCandidate,
    ): CompleteDependencyMap? {
        if (!candidate.hasCompleteMetadata()) return previous
        val workerIds = candidate.workers.map(WorkerDependencyMap::workerId)
        if (workerIds.any(String::isBlank) || workerIds.toSet().size != workerIds.size) return previous
        if (
            workerIds.toSet() != candidate.expectedWorkers ||
            candidate.workers.any { it.records.isEmpty() }
        ) {
            return previous
        }

        val artifacts = candidate.artifacts.uniqueById() ?: return previous
        val records = candidate.workers.flatMap(WorkerDependencyMap::records)
        val testClasses = records.mapTo(LinkedHashSet(), TestDependencyRecord::testClass)
        if (
            records.hasDuplicateTests() ||
            testClasses != candidate.expectedTestClasses ||
            !records.match(artifacts)
        ) {
            return previous
        }

        return CompleteDependencyMap(
            identity = candidate.identity,
            artifacts = candidate.artifacts.toList(),
            records = records,
            completedRunId = candidate.completedRunId,
        )
    }
}

private fun DependencyMapCandidate.hasCompleteMetadata(): Boolean {
    if (cancelled || failed) return false
    if (!collectsAllTests || identity.schemaVersion != DEPENDENCY_MAP_SCHEMA_VERSION) return false
    return completedRunId.isNotBlank() && expectedWorkers.isNotEmpty() && expectedTestClasses.isNotEmpty()
}

private fun identityMismatch(
    baseline: DependencyMapIdentity,
    current: DependencyMapIdentity,
): FullModuleReason? = when {
    baseline.schemaVersion != DEPENDENCY_MAP_SCHEMA_VERSION ||
        current.schemaVersion != DEPENDENCY_MAP_SCHEMA_VERSION -> FullModuleReason.SCHEMA_MISMATCH
    baseline.collectorVersion != current.collectorVersion -> FullModuleReason.COLLECTOR_MISMATCH
    baseline.taskKey != current.taskKey -> FullModuleReason.TASK_MISMATCH
    baseline.runtimeFingerprint != current.runtimeFingerprint -> FullModuleReason.RUNTIME_MISMATCH
    baseline.inputFingerprint != current.inputFingerprint -> FullModuleReason.INPUT_MISMATCH
    else -> null
}

private fun List<ClassDependency>.uniqueById(): Map<DependencyId, ClassDependency>? {
    val grouped = groupBy(ClassDependency::id)
    if (grouped.values.any { it.size != 1 }) return null
    return grouped.mapValues { it.value.single() }
}

private fun List<TestDependencyRecord>.hasDuplicateTests(): Boolean =
    map(TestDependencyRecord::testClass).let { it.size != it.toSet().size }

private fun List<TestDependencyRecord>.match(artifacts: Map<DependencyId, ClassDependency>): Boolean =
    isNotEmpty() && all { record ->
        record.dependencies.isNotEmpty() && record.dependencies.all { dependency ->
            artifacts[dependency.id] == dependency
        }
    }
