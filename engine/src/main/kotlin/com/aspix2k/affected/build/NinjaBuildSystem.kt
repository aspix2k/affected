package com.aspix2k.affected.build

import com.aspix2k.affected.build.process.CliCommand
import java.io.File

internal class NinjaBuildSystem : EngineBuildSystem, NamedSourceBuildSystem, AllFileChangesBuildSystem {

    override val id: String = "NINJA"

    override val sourceExtensions: Set<String> = emptySet()

    override val sourceFileNames: Set<String> = setOf("build.ninja")

    override fun isPresent(workspace: Workspace): Boolean = rootsOf(workspace).isNotEmpty()

    override fun modules(workspace: Workspace): List<BuildModule> = rootsOf(workspace).map(::ninjaRootModule)

    override fun run(workspace: Workspace, root: String, tasks: List<String>) {
        workspace.runBatch(root, ninjaCommands(File(root), tasks), "Affected Ninja")
    }

    override suspend fun runAndWait(workspace: Workspace, root: String, tasks: List<String>): Boolean =
        workspace.runBatchAndWait(root, ninjaCommands(File(root), tasks), "Affected Ninja")

    private fun rootsOf(workspace: Workspace): List<File> =
        workspace.root?.let(::ninjaProjectRoots).orEmpty()
}

internal object NinjaTasks {
    const val TEST = "test"
    const val CHECK = "check"
    const val DEFAULT = "default"
}

internal fun ninjaProjectRoots(base: File): List<File> =
    nestedBuildRoots(base, setOf("build.ninja")) { ninjaManifest(it) != null }

internal fun ninjaManifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    return File(root, "build.ninja").takeIf(File::isRegularFileNoFollow)
}

internal fun ninjaRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    val targets = ninjaTargets(root)
    val testTask = when {
        NinjaTasks.TEST in targets -> NinjaTasks.TEST
        NinjaTasks.CHECK in targets -> NinjaTasks.CHECK
        else -> NinjaTasks.TEST
    }
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = testTask,
        compileTask = NinjaTasks.DEFAULT,
        hasTests = testTask in targets,
        executionId = ".",
    )
}

internal fun ninjaCommands(root: File, tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val module = ninjaRootModule(root)
    val arguments = if (verbs == setOf(module.compileTask)) {
        listOf("ninja")
    } else {
        listOf("ninja", module.testTask)
    }
    return listOf(CliCommand(arguments.joinToString(" "), arguments))
}

internal fun ninjaTargets(root: File): Set<String> {
    val manifest = ninjaManifest(root) ?: return emptySet()
    val text = runCatching { manifest.readText() }.getOrNull() ?: return emptySet()
    return NINJA_TARGET.findAll(text).mapTo(LinkedHashSet()) { it.groupValues[1] }
}

private val NINJA_TARGET = Regex("""(?m)^build\s+(\S+)\s*:""")
private val FOREIGN_ROOTS = listOf(
    "settings.gradle.kts",
    "settings.gradle",
    "pom.xml",
    "CMakeLists.txt",
    "meson.build",
    "GNUmakefile",
    "makefile",
    "Makefile",
)
