package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class AntBuildSystem : SuspendingBuildSystem {

    override val id: String = "ANT"

    override val sourceExtensions: Set<String> = setOf("java", "kt", "xml", "properties")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(antRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, antCommands(tasks), "Affected Ant")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, antCommands(tasks), "Affected Ant")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let { antManifest(File(it)) }
}

internal object AntTasks {
    const val TEST = "test"
    const val JUNIT = "junit"
    const val COMPILE = "compile"
}

internal fun antManifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    return File(root, "build.xml").takeIf(File::isRegularFileNoFollow)
}

internal fun antRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    val targets = antTargets(root)
    val testTask = when {
        AntTasks.TEST in targets -> AntTasks.TEST
        AntTasks.JUNIT in targets -> AntTasks.JUNIT
        else -> AntTasks.TEST
    }
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = testTask,
        compileTask = AntTasks.COMPILE,
        hasTests = testTask in targets,
        executionId = ".",
    )
}

internal fun antCommands(tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val verb = when {
        verbs == setOf(AntTasks.COMPILE) -> AntTasks.COMPILE
        AntTasks.JUNIT in verbs && AntTasks.TEST !in verbs -> AntTasks.JUNIT
        else -> AntTasks.TEST
    }
    return listOf(CliCommand("ant $verb", listOf("ant", verb)))
}

internal fun antTargets(root: File): Set<String> {
    val manifest = File(root, "build.xml")
    if (!manifest.isRegularFileNoFollow()) return emptySet()
    val text = runCatching { manifest.readText() }.getOrNull() ?: return emptySet()
    return ANT_TARGET.findAll(text).mapTo(LinkedHashSet()) { it.groupValues[1] }
}

private val ANT_TARGET = Regex("""<target\b[^>]*\bname\s*=\s*"([^"]+)"""")
private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
