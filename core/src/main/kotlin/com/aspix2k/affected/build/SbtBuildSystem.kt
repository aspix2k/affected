package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class SbtBuildSystem : SuspendingBuildSystem {

    override val id: String = "SBT"

    override val sourceExtensions: Set<String> =
        setOf("scala", "sc", "sbt", "java", "kt", "groovy", "properties")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val manifest = manifestOf(project) ?: return emptyList()
        return listOf(sbtRootModule(manifest.parentFile))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, sbtCommands(tasks), "Affected sbt")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, sbtCommands(tasks), "Affected sbt")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let { File(it, "build.sbt") }?.takeIf(File::isRegularFileNoFollow)
}

internal object SbtTasks {
    const val TEST = "test"
    const val COMPILE = "compile"
}

internal fun sbtRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = SbtTasks.TEST,
        compileTask = SbtTasks.COMPILE,
        hasTests = sbtHasTests(root),
        executionId = ".",
    )
}

internal fun sbtHasTests(root: File): Boolean {
    val tests = File(root, "src/test")
    return tests.isDirectory && tests.walkTopDown().any(::sbtSourceFile)
}

internal fun sbtCommands(tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val verb = if (verbs == setOf(SbtTasks.COMPILE)) SbtTasks.COMPILE else SbtTasks.TEST
    return listOf(CliCommand("sbt $verb", listOf("sbt", "--batch", verb)))
}

private fun sbtSourceFile(file: File): Boolean =
    file.isFile && file.extension in setOf("scala", "java", "kt", "groovy")
