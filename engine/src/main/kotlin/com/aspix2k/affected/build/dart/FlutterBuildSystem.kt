package com.aspix2k.affected.build.dart

import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.EngineBuildSystem
import com.aspix2k.affected.build.Workspace
import com.aspix2k.affected.build.isNeverMember
import com.aspix2k.affected.build.isRegularFileNoFollow
import com.aspix2k.affected.build.nestedBuildRoots
import com.aspix2k.affected.build.pathSegments
import com.aspix2k.affected.build.process.CliCommand
import com.aspix2k.affected.build.runBatch
import com.aspix2k.affected.build.runBatchAndWait
import java.io.File

internal class FlutterBuildSystem : EngineBuildSystem {

    override val id: String = "FLUTTER"

    override fun isTestSource(path: String): Boolean = "test" in pathSegments(path)

    override val sourceExtensions: Set<String> = setOf("dart", "yaml")

    override fun isPresent(workspace: Workspace): Boolean = rootsOf(workspace).isNotEmpty()

    override fun modules(workspace: Workspace): List<BuildModule> = rootsOf(workspace).map(::flutterRootModule)

    override fun run(workspace: Workspace, root: String, tasks: List<String>) {
        workspace.runBatch(root, flutterCommands(File(root), tasks), "Affected Flutter")
    }

    override suspend fun runAndWait(workspace: Workspace, root: String, tasks: List<String>): Boolean =
        workspace.runBatchAndWait(root, flutterCommands(File(root), tasks), "Affected Flutter")

    private fun rootsOf(workspace: Workspace): List<File> =
        workspace.root?.let(::flutterProjectRoots).orEmpty()
}

internal object FlutterTasks {
    const val TEST = "test"
    const val ANALYZE = "analyze"
}

internal fun flutterProjectRoots(base: File): List<File> =
    nestedBuildRoots(base, setOf("pubspec.yaml"), isNeverMember) { flutterManifest(it) != null }

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
