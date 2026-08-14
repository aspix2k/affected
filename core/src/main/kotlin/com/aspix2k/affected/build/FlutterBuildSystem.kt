package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class FlutterBuildSystem : SuspendingBuildSystem {

    override val id: String = "FLUTTER"

    override val sourceExtensions: Set<String> = setOf("dart", "yaml")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(flutterRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, flutterCommands(File(root), tasks), "Affected Flutter")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, flutterCommands(File(root), tasks), "Affected Flutter")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let { flutterManifest(File(it)) }
}

internal object FlutterTasks {
    const val TEST = "test"
    const val ANALYZE = "analyze"
}

internal fun flutterManifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    val manifest = File(root, "pubspec.yaml").takeIf(File::isRegularFileNoFollow) ?: return null
    val text = runCatching { manifest.readText() }.getOrNull() ?: return null
    if (!FLUTTER_SDK.containsMatchIn(text)) return null
    return manifest
}

internal fun flutterRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = FlutterTasks.TEST,
        compileTask = FlutterTasks.ANALYZE,
        hasTests = flutterHasTests(root),
        executionId = ".",
    )
}

internal fun flutterHasTests(root: File): Boolean {
    val tests = File(root, "test")
    return tests.isDirectory && tests.walkTopDown().any(::flutterTestFile)
}

internal fun flutterCommands(tasks: List<String>): List<CliCommand> = flutterCommands(File("."), tasks)

internal fun flutterCommands(root: File, tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val verb = if (verbs == setOf(FlutterTasks.ANALYZE)) FlutterTasks.ANALYZE else FlutterTasks.TEST
    val commands = mutableListOf<CliCommand>()
    if (pubNeedsCodegen(root)) {
        commands += BUILD_RUNNER_COMMAND
    }
    commands += CliCommand("flutter $verb", listOf("flutter", verb))
    return commands
}

private fun flutterTestFile(file: File): Boolean =
    file.isFile && file.name.endsWith("_test.dart")

private val FLUTTER_SDK = Regex("""(?m)^[ \t]*sdk:[ \t]*flutter[ \t]*$""")
private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
