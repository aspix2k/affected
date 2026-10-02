package com.aspix2k.affected.build.gradle

internal class GradleTaskModel(
    private val all: Map<String, Set<String>>,
    private val tests: Map<String, Set<String>>,
) {
    fun available(projectPath: String, source: String?): Set<String> =
        all[projectPath] ?: source?.let(all::get).orEmpty()

    fun typedTests(projectPath: String, source: String?): Set<String> =
        tests[projectPath] ?: source?.let(tests::get).orEmpty()
}

internal fun gradleVerificationTasks(
    availableTasks: Set<String>,
    typedTests: Set<String> = emptySet(),
): Pair<String?, String?> {
    val testTask = gradleTestTask(availableTasks, typedTests)
    val testCompile = testTask?.let { gradleTestCompileTask(it, availableTasks) }
    return testTask to (testCompile ?: gradleProductionCompileTask(availableTasks))
}

internal fun gradleUnitTestTasks(available: Set<String>, typedTests: Set<String> = emptySet()): List<String> {
    val typed = typedTests.filter { it in available && !it.startsWith("connected", ignoreCase = true) }
    if (typed.isNotEmpty()) return typed
    val named = available.filter(::isGradleUnitTestTask)
    return if (named.any(ANDROID_UNIT_TEST::matches)) named - "test" else named
}

internal fun gradleTestTask(available: Set<String>, typedTests: Set<String> = emptySet()): String? {
    val unit = gradleUnitTestTasks(available, typedTests)
    val withCompile = unit.mapNotNull { task ->
        val stem = testTaskStem(task)
        if (existingCompileTask(available, stem, testish = true) == null) return@mapNotNull null
        task to stem.length
    }
    return withCompile.maxByOrNull { it.second }?.first ?: unit.minOrNull()
}

internal fun gradleTestCompileTask(testTask: String, available: Set<String> = emptySet()): String? {
    if (available.isEmpty()) return null
    val stem = testTaskStem(testTask)
    return existingCompileTask(available, matching = stem, testish = true)
        ?: gradleProductionCompileTask(available)
}

private fun testTaskStem(testTask: String): String =
    testTask.removePrefix("test").removeSuffix("Test")

internal fun existingCompileTask(
    available: Set<String>,
    matching: String,
    testish: Boolean,
): String? {
    val needle = matching.lowercase()
    return available.filter { isCompileCodeTask(it) && isTestCompileName(it) == testish }
        .filter { needle.isEmpty() || needle in it.lowercase() }
        .minOrNull()
}

private fun isCompileCodeTask(name: String): Boolean {
    if (!name.startsWith("compile")) return false
    val n = name.lowercase()
    return "resource" !in n && "lint" !in n && "javares" !in n
}

private fun isTestCompileName(name: String): Boolean = "test" in name.lowercase()

internal fun isGradleUnitTestTask(name: String): Boolean {
    val n = name.lowercase()
    if (UNIT_TEST_EXCLUDED_PREFIXES.any { n.startsWith(it) }) return false
    if ("resource" in n || "lint" in n || n.endsWith("classes")) return false
    return n == "test" || n.startsWith("test") || n.endsWith("test")
}

internal fun isAndroidInstrumentationSource(path: String): Boolean {
    val segments = path.replace('\\', '/').split('/')
    return segments.any { it == "androidTest" || it == "androidInstrumentedTest" }
}

internal fun gradleInstrumentationTestTask(available: Set<String>): String? =
    available.filter {
        val n = it.lowercase()
        n.startsWith("connected") && "androidtest" in n
    }.minOrNull()

internal fun selectAndroidTestTask(
    unitTestTask: String,
    available: Set<String>,
    instrumentationOnly: Boolean,
): String =
    if (instrumentationOnly) gradleInstrumentationTestTask(available) ?: unitTestTask else unitTestTask

internal fun gradleKmpAdditionalTestTasks(
    available: Set<String>,
    primary: String,
    typedTests: Set<String> = emptySet(),
): Set<String> {
    val extra = gradleUnitTestTasks(available, typedTests).filterTo(LinkedHashSet()) { it != primary }
    if (primary.contains("android", ignoreCase = true)) {
        extra.removeAll { it.contains("android", ignoreCase = true) }
    }
    return extra
}

internal fun gradleProductionCompileTask(available: Set<String>): String? {
    if (available.isEmpty()) return null
    return existingCompileTask(available, matching = "", testish = false)
}

private val ANDROID_UNIT_TEST = Regex("test.+UnitTest")

private val UNIT_TEST_EXCLUDED_PREFIXES = listOf(
    "compile",
    "assemble",
    "link",
    "clean",
    "detekt",
    "ktlint",
    "connected",
    "all",
)
