package com.aspix2k.affected.build

import com.aspix2k.affected.build.process.CliCommand
import java.io.File

internal class SqlcBuildSystem : EngineBuildSystem, NamedSourceBuildSystem {

    override val id: String = "SQLC"

    override val sourceExtensions: Set<String> = setOf("sql", "yml", "yaml", "json")

    override val sourceFileNames: Set<String> = setOf("sqlc.yaml", "sqlc.yml", "sqlc.json")

    override fun isPresent(workspace: Workspace): Boolean = rootsOf(workspace).isNotEmpty()

    override fun modules(workspace: Workspace): List<BuildModule> = rootsOf(workspace).map(::sqlcRootModule)

    override fun run(workspace: Workspace, root: String, tasks: List<String>) {
        workspace.runBatch(root, sqlcCommands(tasks), "Affected sqlc")
    }

    override suspend fun runAndWait(workspace: Workspace, root: String, tasks: List<String>): Boolean =
        workspace.runBatchAndWait(root, sqlcCommands(tasks), "Affected sqlc")

    private fun rootsOf(workspace: Workspace): List<File> =
        workspace.root?.let(::sqlcProjectRoots).orEmpty()
}

internal object SqlcTasks {
    const val COMPILE = "compile"
}

internal fun sqlcProjectRoots(base: File): List<File> =
    nestedBuildRoots(base, SQLC_NAMES, isNeverMember) { sqlcManifest(it) != null }

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
private val SQLC_NAMES = MANIFESTS.toSet()
private val REMOTE_OR_UNPROVED = Regex(
    """(?i)(?:^|[\s{,])(?:"?(?:database|uri|cloud|managed|process)"?\s*:)|[$*?]|\${'$'}\{""",
)
private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
