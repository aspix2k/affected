package com.aspix2k.affected.build.deno

import com.aspix2k.affected.build.AllFileChangesBuildSystem
import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.ManifestSearch
import com.aspix2k.affected.build.SuspendingBuildSystem
import com.aspix2k.affected.build.isRegularFileNoFollow
import com.aspix2k.affected.build.nestedBuildRoot
import com.aspix2k.affected.build.process.CliCommand
import com.aspix2k.affected.build.process.CommandRunner
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import java.io.File

class DenoBuildSystem : SuspendingBuildSystem, AllFileChangesBuildSystem {

    override val id: String = "DENO"

    override val sourceExtensions: Set<String> =
        setOf("ts", "tsx", "js", "jsx", "mjs", "cjs", "mts", "cts", "json", "jsonc", "lock")

    override fun isPresent(project: Project): Boolean = rootOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = rootOf(project) ?: return emptyList()
        return listOf(denoRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, denoCommands(File(root), tasks), "Affected Deno")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, denoCommands(File(root), tasks), "Affected Deno")

    private fun rootOf(project: Project): File? =
        project.basePath?.let(::File)?.let(::denoProjectRoot)
}

internal object DenoTasks {
    const val TEST = "test"
}

internal fun denoProjectRoot(base: File): File? =
    nestedBuildRoot(base) { denoConfig(it) != null }

internal fun denoConfig(root: File): JsonObject? {
    if (File(root, "package.json").exists()) return null
    val configs = CONFIG_NAMES.map { File(root, it) }.filter { it.exists() }
    val config = configs.singleOrNull()?.takeIf(File::isRegularFileNoFollow) ?: return null
    val text = ManifestSearch.readText(config) ?: return null
    return runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
}

internal fun denoRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = DenoTasks.TEST,
        compileTask = null,
        hasTests = denoHasTests(root),
        executionId = ".",
    )
}

internal fun denoCommands(root: File, tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val arguments = if (denoTestTask(root)) listOf("deno", "task", DenoTasks.TEST) else listOf("deno", DenoTasks.TEST)
    return listOf(CliCommand(arguments.joinToString(" "), arguments))
}

private fun denoTestTask(root: File): Boolean {
    val tasks = denoConfig(root)?.get("tasks") ?: return false
    return tasks.isJsonObject && tasks.asJsonObject.has(DenoTasks.TEST)
}

private fun denoHasTests(root: File): Boolean {
    val config = denoConfig(root) ?: return false
    if (config.has("test") || denoTestTask(root)) return true
    return ManifestSearch.anyFile(root) { file ->
        TEST_FILE.matches(file.name) || (file.name in CONFIG_NAMES && file.parentFile != root)
    } != false
}

private val CONFIG_NAMES = listOf("deno.json", "deno.jsonc")
private val TEST_FILE = Regex("""(?:.*[_.])?test\.(?:ts|tsx|mts|js|jsx|mjs)""")
