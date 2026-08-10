package com.aspix2k.affected.impact

const val DEPENDENCY_MAP_SCHEMA_VERSION = 3

@JvmInline
value class TestClassId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

data class DependencyId(val className: String, val codeSource: String) {
    init {
        require(className.isNotBlank())
        require(codeSource.isNotBlank())
    }
}

data class ClassDependency(val id: DependencyId, val sha256: String) {
    init {
        require(sha256.isNotBlank())
    }
}

data class TestDependencyRecord(
    val testClass: TestClassId,
    val dependencies: Set<ClassDependency>,
)

sealed interface TestSelection {

    data object All : TestSelection

    data class Classes(val ids: Set<TestClassId>) : TestSelection {
        init {
            require(ids.isNotEmpty())
        }
    }

    data object ProvenEmpty : TestSelection
}

enum class FullModuleReason {
    UNSUPPORTED_CHANGE,
    UNCLASSIFIED_CHANGE,
    MISSING_DEPENDENCY_MAP,
    SCHEMA_MISMATCH,
    COLLECTOR_MISMATCH,
    TASK_MISMATCH,
    RUNTIME_MISMATCH,
    INPUT_MISMATCH,
    CORRUPT_DEPENDENCY_MAP,
    DUPLICATE_CLASS,
    DUPLICATE_TEST_CLASS,
    ARTIFACT_SET_CHANGED,
}

sealed interface TestImpact {

    data class Exact(
        val selection: TestSelection,
        val changedDependencies: Set<DependencyId>,
    ) : TestImpact

    data class FullModule(val reason: FullModuleReason) : TestImpact
}
