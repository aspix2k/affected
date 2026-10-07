package com.aspix2k.affected.build

import com.aspix2k.affected.build.process.CliCommand
import java.io.File

internal class AtlasBuildSystem : EngineBuildSystem, NamedSourceBuildSystem {

    override val id: String = "ATLAS"

    override val sourceExtensions: Set<String> = setOf("sql", "hcl")

    override val sourceFileNames: Set<String> = setOf("atlas.hcl")

    override fun isPresent(workspace: Workspace): Boolean = rootsOf(workspace).isNotEmpty()

    override fun modules(workspace: Workspace): List<BuildModule> = rootsOf(workspace).map(::atlasRootModule)

    override fun run(workspace: Workspace, root: String, tasks: List<String>) {
        workspace.runBatch(root, atlasCommands(tasks), "Affected Atlas")
    }

    override suspend fun runAndWait(workspace: Workspace, root: String, tasks: List<String>): Boolean =
        workspace.runBatchAndWait(root, atlasCommands(tasks), "Affected Atlas")

    private fun rootsOf(workspace: Workspace): List<File> =
        workspace.root?.let(::atlasProjectRoots).orEmpty()
}

internal object AtlasTasks {
    const val VALIDATE = "validate"
}

internal fun atlasProjectRoots(base: File): List<File> =
    nestedBuildRoots(base, setOf("atlas.hcl"), isNeverMember) { atlasManifest(it) != null }

internal fun atlasManifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    val manifest = File(root, "atlas.hcl").takeIf(File::isRegularFileNoFollow) ?: return null
    val text = runCatching { manifest.readText() }.getOrNull() ?: return null
    return manifest.takeIf { atlasLocalValidate(text) }
}

internal fun atlasRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = AtlasTasks.VALIDATE,
        compileTask = AtlasTasks.VALIDATE,
        hasTests = true,
        executionId = ".",
    )
}

internal fun atlasCommands(tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    return listOf(CliCommand("atlas migrate validate", listOf("atlas", "migrate", AtlasTasks.VALIDATE)))
}

internal fun atlasLocalValidate(text: String): Boolean = !REMOTE_OR_UNPROVED.containsMatchIn(text)

private val REMOTE_OR_UNPROVED = Regex(
    """(?i)(?:\b(?:url|dev|dev-url)\s*=)|(?:postgres|mysql|mariadb|sqlite|docker|atlas|sqlserver)://|[$*?]|\${'$'}\{""",
)
private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
