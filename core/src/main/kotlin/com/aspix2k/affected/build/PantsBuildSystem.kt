package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class PantsBuildSystem : SuspendingBuildSystem, NamedSourceBuildSystem {

    override val id: String = "PANTS"

    override val sourceExtensions: Set<String> = setOf("py", "rs", "go", "java", "kt", "toml")

    override val sourceFileNames: Set<String> = setOf("pants.toml", "BUILD", "BUILD.pants")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(pantsRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, pantsCommands(tasks), "Affected Pants")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, pantsCommands(tasks), "Affected Pants")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let(::File)?.let(::pantsProjectRoot)?.let(::pantsManifest)
}

internal object PantsTasks {
    const val TEST = "test"
    const val CHECK = "check"
}

internal fun pantsProjectRoot(base: File): File? =
    nestedBuildRoot(base) { pantsManifest(it) != null }

internal fun pantsManifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    return File(root, "pants.toml").takeIf(File::isRegularFileNoFollow)
}

internal fun pantsRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = PantsTasks.TEST,
        compileTask = PantsTasks.CHECK,
        hasTests = true,
        executionId = ".",
    )
}

internal fun pantsCommands(tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val verb = if (verbs == setOf(PantsTasks.CHECK)) PantsTasks.CHECK else PantsTasks.TEST
    return listOf(CliCommand("pants $verb", listOf("pants", verb)))
}

private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
