package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class DbtBuildSystem : SuspendingBuildSystem, NamedSourceBuildSystem {

    override val id: String = "DBT"

    override val sourceExtensions: Set<String> = setOf("sql", "yml", "yaml")

    override val sourceFileNames: Set<String> = setOf("dbt_project.yml", "profiles.yml")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(dbtRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, dbtCommands(tasks), "Affected dbt")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, dbtCommands(tasks), "Affected dbt")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let { dbtManifest(File(it)) }
}

internal object DbtTasks {
    const val TEST = "test"
    const val COMPILE = "compile"
}

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
