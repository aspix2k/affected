package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class NinjaBuildSystem : SuspendingBuildSystem, NamedSourceBuildSystem, AllFileChangesBuildSystem {

    override val id: String = "NINJA"

    override val sourceExtensions: Set<String> = emptySet()

    override val sourceFileNames: Set<String> = setOf("build.ninja")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(ninjaRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, ninjaCommands(File(root), tasks), "Affected Ninja")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, ninjaCommands(File(root), tasks), "Affected Ninja")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let(::File)?.let(::ninjaProjectRoot)?.let(::ninjaManifest)
}

internal object NinjaTasks {
    const val TEST = "test"
    const val CHECK = "check"
    const val DEFAULT = "default"
}

internal fun ninjaProjectRoot(base: File): File? =
    nestedBuildRoot(base) { ninjaManifest(it) != null }

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
