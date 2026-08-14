package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class SwiftBuildSystem : SuspendingBuildSystem, NamedSourceBuildSystem {

    override val id: String = "SWIFT"

    override val sourceExtensions: Set<String> = setOf("swift", "h", "m", "mm")

    override val sourceFileNames: Set<String> = setOf("Package.swift")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(swiftRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, swiftCommands(tasks), "Affected Swift")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, swiftCommands(tasks), "Affected Swift")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let { swiftManifest(File(it)) }
}

internal object SwiftTasks {
    const val TEST = "test"
    const val BUILD = "build"
}

internal fun swiftManifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    return File(root, "Package.swift").takeIf(File::isRegularFileNoFollow)
}

internal fun swiftRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = SwiftTasks.TEST,
        compileTask = SwiftTasks.BUILD,
        hasTests = swiftHasTests(root),
        executionId = ".",
    )
}

internal fun swiftHasTests(root: File): Boolean {
    val tests = File(root, "Tests")
    return tests.isDirectory && tests.walkTopDown().any(::swiftTestFile)
}

internal fun swiftCommands(tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val verb = if (verbs == setOf(SwiftTasks.BUILD)) SwiftTasks.BUILD else SwiftTasks.TEST
    return listOf(CliCommand("swift $verb", listOf("swift", verb)))
}

private fun swiftTestFile(file: File): Boolean =
    file.isFile && file.name.endsWith(".swift")

private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
