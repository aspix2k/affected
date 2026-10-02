package com.aspix2k.affected.build.deno

import com.aspix2k.affected.build.AllFileChangesBuildSystem
import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.ManifestSearch
import com.aspix2k.affected.build.SuspendingBuildSystem
import com.aspix2k.affected.build.isRegularFileNoFollow
import com.aspix2k.affected.build.nestedBuildRoots
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

    override fun isPresent(project: Project): Boolean = rootsOf(project).isNotEmpty()

    override fun modules(project: Project): List<BuildModule> = rootsOf(project).map(::denoRootModule)

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, denoCommands(File(root), tasks), "Affected Deno")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, denoCommands(File(root), tasks), "Affected Deno")

    private fun rootsOf(project: Project): List<File> =
        project.basePath?.let(::File)?.let(::denoProjectRoots).orEmpty()
}

internal object DenoTasks {
    const val TEST = "test"
}

internal class DenoConfig(val json: JsonObject?)

internal fun denoProjectRoots(base: File): List<File> =
    nestedBuildRoots(base, CONFIG_NAMES.toSet(), ::isDenoWorkspaceMember) {
        denoConfig(it) != null && !nodeOwnsTests(it)
    }

private fun isDenoWorkspaceMember(root: File, nested: File): Boolean {
    val relative = root.toPath().relativize(nested.toPath()).joinToString("/")
    val listed = denoWorkspaceMembers(root).any { member ->
        member == relative || member.endsWith("/*") && relative.substringBeforeLast('/', "") == member.dropLast(2)
    }
    return listed || !denoHasTests(nested)
}

private fun denoWorkspaceMembers(root: File): List<String> {
    val workspace = denoConfig(root)?.json?.get("workspace")
    val members = if (workspace?.isJsonObject == true) workspace.asJsonObject.get("members") else workspace
    return members?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
        .mapNotNull { entry -> entry.takeIf { it.isJsonPrimitive }?.asString?.removePrefix("./")?.trimEnd('/') }
}

internal fun denoConfig(root: File): DenoConfig? {
    val configs = CONFIG_NAMES.map { File(root, it) }.filter { it.exists() }
    if (configs.isEmpty()) return null
    val config = configs.singleOrNull()?.takeIf(File::isRegularFileNoFollow) ?: return DenoConfig(null)
    val text = ManifestSearch.readText(config) ?: return DenoConfig(null)
    return DenoConfig(runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull())
}

private fun nodeOwnsTests(root: File): Boolean {
    val manifest = File(root, "package.json")
    if (!manifest.exists()) return false
    val text = manifest.takeIf(File::isRegularFileNoFollow)?.let(ManifestSearch::readText) ?: return true
    val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return true
    val scripts = json.get("scripts")
    return json.has("workspaces") ||
        (scripts != null && (!scripts.isJsonObject || scripts.asJsonObject.has(DenoTasks.TEST)))
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
    val tasks = denoConfig(root)?.json?.get("tasks") ?: return false
    return tasks.isJsonObject && tasks.asJsonObject.has(DenoTasks.TEST)
}

private fun denoHasTests(root: File): Boolean {
    val config = denoConfig(root) ?: return false
    val json = config.json ?: return true
    if (json.has("test") || denoTestTask(root)) return true
    return ManifestSearch.anyFile(root) { file ->
        TEST_FILE.matches(file.name) || (file.name in CONFIG_NAMES && file.parentFile != root)
    } != false
}

private val CONFIG_NAMES = listOf("deno.json", "deno.jsonc")
private val TEST_FILE = Regex("""(?:.*[_.])?test\.(?:ts|tsx|mts|js|jsx|mjs)""")
