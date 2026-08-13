package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class DartBuildSystem : SuspendingBuildSystem {

    override val id: String = "DART"

    override val sourceExtensions: Set<String> = setOf("dart", "yaml")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(dartRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, dartCommands(tasks), "Affected Dart")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, dartCommands(tasks), "Affected Dart")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let { dartManifest(File(it)) }
}

internal object DartTasks {
    const val TEST = "test"
    const val ANALYZE = "analyze"
}

internal fun dartManifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    val manifest = File(root, "pubspec.yaml").takeIf(File::isRegularFileNoFollow) ?: return null
    val text = runCatching { manifest.readText() }.getOrNull() ?: return null
    if (FLUTTER_SDK.containsMatchIn(text)) return null
    return manifest
}

internal fun dartRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = DartTasks.TEST,
        compileTask = DartTasks.ANALYZE,
        hasTests = dartHasTests(root),
        executionId = ".",
    )
}

internal fun dartHasTests(root: File): Boolean {
    val tests = File(root, "test")
    return tests.isDirectory && tests.walkTopDown().any(::dartTestFile)
}

internal fun dartCommands(tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val verb = if (verbs == setOf(DartTasks.ANALYZE)) DartTasks.ANALYZE else DartTasks.TEST
    return listOf(CliCommand("dart $verb", listOf("dart", verb)))
}

private fun dartTestFile(file: File): Boolean =
    file.isFile && file.name.endsWith("_test.dart")

private val FLUTTER_SDK = Regex("""(?m)^[ \t]*sdk:[ \t]*flutter[ \t]*$""")
private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
