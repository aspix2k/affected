package com.aspix2k.affected.build.gradle

import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.BuildModule
import java.io.File

internal val JVM_SOURCE_EXTENSIONS = setOf("kt", "java", "scala", "groovy")

private val GRADLE_TEST_SOURCE_DIRS = listOf(
    "src/test",
    "src/testDebug",
    "src/commonTest",
    "src/jvmTest",
    "src/androidUnitTest",
    "src/androidHostTest",
    "src/androidInstrumentedTest",
    "src/iosTest",
    "src/iosSimulatorTest",
)

private val GRADLE_TEST_SOURCE_SET_MARKERS = listOf(
    "test",
    "testDebug",
    "commonTest",
    "jvmTest",
    "androidUnitTest",
    "androidHostTest",
    "androidInstrumentedTest",
    "iosTest",
    "iosSimulatorTest",
    "unitTest",
    "androidTest",
)

internal fun gradleRequiresWorkspace(root: String, changes: BuildChanges): Boolean {
    val rootPath = File(root).toPath().toAbsolutePath().normalize()
    return changes.files.any { raw ->
        val file = File(raw).toPath().toAbsolutePath().normalize()
        file.startsWith(rootPath) && gradleBuildWideChange(rootPath.relativize(file).toString().replace('\\', '/'))
    }
}

internal fun gradleModulesWithDependencies(built: Map<String, Pair<BuildModule, Set<String>>>): List<BuildModule> =
    built.values.map { (module, dependencies) ->
        module.copy(dependencies = dependencies.mapNotNullTo(HashSet()) { built[it]?.first?.key } - module.key)
    }

internal fun gradleUnverifiableScriptChanged(module: BuildModule, changes: BuildChanges): Boolean {
    if (module.hasTests || module.compileTask != null) return false
    val scripts = module.contentRoots.flatMapTo(HashSet()) { root ->
        GRADLE_BUILD_SCRIPTS.map { File(root, it).invariantSeparatorsPath }
    }
    return changes.files.any { File(it).invariantSeparatorsPath in scripts }
}

private val GRADLE_BUILD_SCRIPTS = listOf("build.gradle", "build.gradle.kts")

internal fun gradleBuildWideChange(relative: String): Boolean =
    relative in GRADLE_BUILD_WIDE_FILES || GRADLE_BUILD_WIDE_DIRECTORIES.any { relative.startsWith("$it/") }

private val GRADLE_BUILD_WIDE_FILES = setOf(
    "settings.gradle",
    "settings.gradle.kts",
    "build.gradle",
    "build.gradle.kts",
    "gradle.properties",
)
private val GRADLE_BUILD_WIDE_DIRECTORIES = listOf("gradle", "buildSrc")

internal fun gradleIsSourceFile(file: File): Boolean =
    file.isFile && file.extension in JVM_SOURCE_EXTENSIONS

internal fun gradleHoldsTests(roots: List<String>, testRoots: List<String>): Boolean =
    roots.any(::gradleHoldsTests) || testRoots.any { File(it).walkTopDown().any(::gradleIsSourceFile) }

internal fun gradleHoldsTests(root: String): Boolean {
    val normalized = root.replace('\\', '/')
    if (GRADLE_TEST_SOURCE_SET_MARKERS.any { marker ->
            normalized.endsWith("/$marker") || "/$marker/" in normalized
        }
    ) {
        return File(root).walkTopDown().any(::gradleIsSourceFile)
    }
    return GRADLE_TEST_SOURCE_DIRS.any { directory ->
        File(root, directory).let { it.isDirectory && it.walkTopDown().any(::gradleIsSourceFile) }
    }
}

internal const val GRADLE_SYSTEM_ID = "GRADLE"

@Suppress("LongParameterList")
internal fun gradleModule(
    path: String,
    projectPath: String,
    root: String,
    roots: List<String>,
    testRoots: List<String>,
    tasks: GradleTaskModel,
    execution: Pair<String, String>,
): BuildModule {
    val source = roots.filterNot { it.contains("/build/") || it.contains("/.gradle/") }.minByOrNull { it.length }
    val availableTasks = tasks.available(projectPath, source)
    val typedTests = tasks.typedTests(projectPath, source)
    val filesystemTests = gradleHoldsTests(roots, testRoots)
    val (verifiedTest, testCompile) = gradleVerificationTasks(availableTasks, typedTests)
    val hasTests = filesystemTests && !verifiedTest.isNullOrBlank()
    val testTask = verifiedTest.orEmpty()
    val compileTask = if (hasTests) {
        testCompile
    } else {
        gradleProductionCompileTask(availableTasks)
    }
    return BuildModule(
        id = path,
        root = root,
        contentRoots = roots,
        testTask = testTask,
        compileTask = compileTask,
        hasTests = hasTests,
        extraTasks = availableTasks,
        executionRoot = execution.first,
        executionId = execution.second,
        additionalTestTasks = if (hasTests) {
            gradleKmpAdditionalTestTasks(availableTasks, testTask, typedTests)
        } else {
            emptySet()
        },
        systemId = GRADLE_SYSTEM_ID,
    )
}
