package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class SqlcBuildSystem : SuspendingBuildSystem, NamedSourceBuildSystem {

    override val id: String = "SQLC"

    override val sourceExtensions: Set<String> = setOf("sql", "yml", "yaml", "json")

    override val sourceFileNames: Set<String> = setOf("sqlc.yaml", "sqlc.yml", "sqlc.json")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(sqlcRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, sqlcCommands(tasks), "Affected sqlc")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, sqlcCommands(tasks), "Affected sqlc")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let { sqlcManifest(File(it)) }
}

internal object SqlcTasks {
    const val COMPILE = "compile"
}

internal fun sqlcManifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    val manifest = MANIFESTS.firstNotNullOfOrNull { name ->
        File(root, name).takeIf(File::isRegularFileNoFollow)
    } ?: return null
    val text = runCatching { manifest.readText() }.getOrNull() ?: return null
    return manifest.takeIf { sqlcLocalCompile(text) }
}

internal fun sqlcRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = SqlcTasks.COMPILE,
        compileTask = SqlcTasks.COMPILE,
        hasTests = true,
        executionId = ".",
    )
}

internal fun sqlcCommands(tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    return listOf(CliCommand("sqlc compile", listOf("sqlc", SqlcTasks.COMPILE)))
}

internal fun sqlcLocalCompile(text: String): Boolean =
    !REMOTE_OR_UNPROVED.containsMatchIn(text)

private val MANIFESTS = listOf("sqlc.yaml", "sqlc.yml", "sqlc.json")
private val REMOTE_OR_UNPROVED = Regex(
    """(?i)(?:^|[\s{,])(?:"?(?:database|uri|cloud|managed|process)"?\s*:)|[$*?]|\${'$'}\{""",
)
private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
