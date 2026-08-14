package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class Buck2BuildSystem : SuspendingBuildSystem, NamedSourceBuildSystem {

    override val id: String = "BUCK2"

    override val sourceExtensions: Set<String> = setOf("bzl", "py", "rs", "go", "java", "kt")

    override val sourceFileNames: Set<String> = setOf(".buckconfig", "BUCK", "TARGETS")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(buck2RootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, buck2Commands(tasks), "Affected Buck2")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, buck2Commands(tasks), "Affected Buck2")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let { buck2Manifest(File(it)) }
}

internal object Buck2Tasks {
    const val TEST = "test"
    const val BUILD = "build"
}

internal fun buck2Manifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    return File(root, ".buckconfig").takeIf(File::isRegularFileNoFollow)
}

internal fun buck2RootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = Buck2Tasks.TEST,
        compileTask = Buck2Tasks.BUILD,
        hasTests = true,
        executionId = ".",
    )
}

internal fun buck2Commands(tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val verb = if (verbs == setOf(Buck2Tasks.BUILD)) Buck2Tasks.BUILD else Buck2Tasks.TEST
    return listOf(CliCommand("buck2 $verb", listOf("buck2", verb)))
}

private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
