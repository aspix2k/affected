package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class AtlasBuildSystem : SuspendingBuildSystem, NamedSourceBuildSystem {

    override val id: String = "ATLAS"

    override val sourceExtensions: Set<String> = setOf("sql", "hcl")

    override val sourceFileNames: Set<String> = setOf("atlas.hcl")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(atlasRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, atlasCommands(tasks), "Affected Atlas")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, atlasCommands(tasks), "Affected Atlas")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let(::File)?.let(::atlasProjectRoot)?.let(::atlasManifest)
}

internal object AtlasTasks {
    const val VALIDATE = "validate"
}

internal fun atlasProjectRoot(base: File): File? =
    nestedBuildRoot(base) { atlasManifest(it) != null }

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
