package com.aspix2k.affected.build

import com.aspix2k.affected.build.process.CliCommand
import java.io.File

internal class DbtBuildSystem : EngineBuildSystem, NamedSourceBuildSystem, AllFileChangesBuildSystem {

    override val id: String = "DBT"

    override val sourceExtensions: Set<String> = setOf("sql", "yml", "yaml")

    override val sourceFileNames: Set<String> = setOf("dbt_project.yml", "profiles.yml")

    override fun isPresent(workspace: Workspace): Boolean = rootsOf(workspace).isNotEmpty()

    override fun modules(workspace: Workspace): List<BuildModule> = rootsOf(workspace).map(::dbtRootModule)

    override fun run(workspace: Workspace, root: String, tasks: List<String>) {
        workspace.runBatch(root, dbtCommands(tasks), "Affected dbt")
    }

    override suspend fun runAndWait(workspace: Workspace, root: String, tasks: List<String>): Boolean =
        workspace.runBatchAndWait(root, dbtCommands(tasks), "Affected dbt")

    private fun rootsOf(workspace: Workspace): List<File> =
        workspace.root?.let(::dbtProjectRoots).orEmpty()
}

internal object DbtTasks {
    const val TEST = "test"
    const val COMPILE = "compile"
}

internal fun dbtProjectRoots(base: File): List<File> =
    nestedBuildRoots(base, setOf("dbt_project.yml")) { dbtManifest(it) != null }

internal fun dbtManifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    val project = File(root, "dbt_project.yml").takeIf(File::isRegularFileNoFollow) ?: return null
    return project.takeIf { dbtLocalDuckDb(root) }
}

internal fun dbtRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = DbtTasks.TEST,
        compileTask = DbtTasks.COMPILE,
        hasTests = true,
        executionId = ".",
    )
}

internal fun dbtCommands(tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val verb = if (verbs == setOf(DbtTasks.COMPILE)) DbtTasks.COMPILE else DbtTasks.TEST
    val arguments = listOf("dbt", verb, "--project-dir", ".", "--profiles-dir", ".")
    return listOf(CliCommand("dbt $verb", arguments))
}

internal fun dbtLocalDuckDb(root: File): Boolean {
    val profiles = File(root, "profiles.yml").takeIf(File::isRegularFileNoFollow) ?: return false
    val text = runCatching { profiles.readText() }.getOrNull() ?: return false
    if (UNPROVED_PROFILE.containsMatchIn(text) || MOTHERDUCK.containsMatchIn(text)) return false
    val types = PROFILE_TYPE.findAll(text).map { it.groupValues[1].lowercase() }.toSet()
    return types == setOf("duckdb")
}

private val PROFILE_TYPE = Regex("""(?m)^[ \t]*type:[ \t]*([A-Za-z][A-Za-z0-9_-]*)[ \t]*$""")
private val UNPROVED_PROFILE = Regex("""[$*?{]""")
private val MOTHERDUCK = Regex("""(?i)(?:^|[^\w])md:""")
private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
