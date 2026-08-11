package com.aspix2k.affected.build

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class DotnetBuildSystem : SuspendingBuildSystem {

    private data class Snapshot(val root: String, val stamp: String, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "DOTNET"

    override val sourceExtensions: Set<String> =
        setOf("cs", "fs", "vb", "csproj", "fsproj", "vbproj", "props", "targets", "sln", "slnx", "razor", "json")

    override fun isPresent(project: Project): Boolean = rootOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = rootOf(project) ?: return emptyList()
        val rootPath = root.invariantSeparatorsPath
        val stamp = ManifestSearch.fingerprint(root, manifests(root))
        if (stamp != null) cache.get()?.takeIf { it.root == rootPath && it.stamp == stamp }?.let { return it.modules }

        val discovered = runCatching { DotnetProjects.parse(root) }.getOrNull()
        val discovery = failClosedModules(
            root,
            DotnetProjects.TEST,
            DotnetProjects.COMPILE,
            discovered,
        )
        if (stamp != null && discovery.complete) cache.set(Snapshot(rootPath, stamp, discovery.modules))
        return discovery.modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, dotnetCommands(root, tasks), "Affected .NET")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, dotnetCommands(root, tasks), "Affected .NET")

    private fun rootOf(project: Project): File? {
        val base = project.basePath?.let(::File) ?: return null
        val children = base.listFiles() ?: return null
        return base.takeIf { _ ->
            children.any {
                it.extension.lowercase() in SOLUTION_EXTENSIONS && it.isRegularFileNoFollow() ||
                    it.name == "global.json" && it.isRegularFileNoFollow() ||
                    DotnetProjects.isProjectFile(it)
            }
        }
    }

    private fun manifests(root: File): List<File> =
        listOf("csproj", "fsproj", "vbproj", "props", "targets", "sln", "slnx")
            .flatMap { ManifestSearch.findByExtension(root, it) } +
            listOf("global.json", "NuGet.Config").flatMap { ManifestSearch.find(root, it) }
}

private val SOLUTION_EXTENSIONS = setOf("sln", "slnx")

internal fun dotnetCommands(root: String, tasks: List<String>): List<CliCommand> = tasks.map { task ->
    val project = task.substringBeforeLast(':')
    val verb = if (task.substringAfterLast(':') == DotnetProjects.COMPILE) "build" else "test"
    val selection = when {
        project == "." -> emptyList()
        verb == "test" && usesMicrosoftTestingPlatform(root) -> listOf("--project", project)
        else -> listOf(project)
    }
    CliCommand("dotnet $verb $project", listOf("dotnet", verb) + selection)
}

private fun usesMicrosoftTestingPlatform(root: String): Boolean = runCatching {
    val global = File(root, "global.json").takeIf(File::isRegularFileNoFollow) ?: return false
    val text = ManifestSearch.readText(global) ?: return false
    JsonParser.parseString(text).asJsonObject
        .getAsJsonObject("test")
        ?.get("runner")
        ?.takeIf { it.isJsonPrimitive }
        ?.asString
        ?.equals("Microsoft.Testing.Platform", ignoreCase = true) == true
}.getOrDefault(false)
