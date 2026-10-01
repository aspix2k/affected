package com.aspix2k.affected.build.go

import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.ManifestSearch
import com.aspix2k.affected.build.SuspendingBuildSystem
import com.aspix2k.affected.build.failClosedModules
import com.aspix2k.affected.build.isRegularFileNoFollow
import com.aspix2k.affected.build.nestedBuildRoots
import com.aspix2k.affected.build.process.CliCommand
import com.aspix2k.affected.build.process.CommandRunner
import com.aspix2k.affected.build.retainBuildSnapshot
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class GoBuildSystem : SuspendingBuildSystem {

    private data class Snapshot(val stamp: String, val modules: List<BuildModule>)

    private val cache = ConcurrentHashMap<String, Snapshot>()

    override val id: String = "GO"

    override val sourceExtensions: Set<String> = setOf("go", "mod", "sum", "work")

    override fun isPresent(project: Project): Boolean = rootsOf(project).isNotEmpty()

    override fun modules(project: Project): List<BuildModule> {
        val roots = rootsOf(project)
        cache.keys.retainAll(roots.mapTo(HashSet()) { it.invariantSeparatorsPath })
        return roots.flatMap(::modulesOf)
    }

    private fun modulesOf(directory: File): List<BuildModule> {
        val root = directory.invariantSeparatorsPath
        val sources = ManifestSearch.findByExtension(directory, "go")
        val inputs =
            listOf("go.mod", "go.sum", "go.work", "go.work.sum")
                .flatMap { ManifestSearch.find(directory, it) } +
                sources
        val stamp = sources.takeIf { it.isNotEmpty() }?.let {
            ManifestSearch.fingerprint(directory, inputs)
        }

        if (stamp != null) cache[root]?.takeIf { it.stamp == stamp }?.let { return it.modules }

        val output = CommandRunner.capture(root, LIST, timeoutSeconds = 120)
        val discovery = failClosedModules(
            directory,
            GoPackages.TEST,
            GoPackages.COMPILE,
            output?.let { GoPackages.parse(it, root) },
        )
        val fingerprintedPackages = sources.mapTo(HashSet()) {
            it.parentFile.absoluteFile.normalize().invariantSeparatorsPath
        }
        val completeFingerprint = discovery.modules.all { module ->
            module.contentRoots.singleOrNull() in fingerprintedPackages
        }
        if (stamp != null && discovery.complete && completeFingerprint) {
            cache.retainBuildSnapshot(root, Snapshot(stamp, discovery.modules), discovery.modules.size)
        }
        return discovery.modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, goCommands(tasks), "Affected Go")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, goCommands(tasks), "Affected Go")

    private fun rootsOf(project: Project): List<File> =
        project.basePath?.let(::File)?.let(::goProjectRoots).orEmpty()

    private companion object {
        val LIST = listOf("go", "list", "-json", "./...")
    }
}

internal fun goProjectRoots(base: File): List<File> =
    nestedBuildRoots(base) { goManifest(it) != null }

internal fun goManifest(root: File): File? =
    File(root, "go.mod").takeIf(File::isRegularFileNoFollow)

internal fun goCommands(tasks: List<String>): List<CliCommand> {
    val grouped = tasks.groupBy({ it.substringAfterLast(':') }, { it.substringBeforeLast(':') })
    return grouped.map { (task, packages) ->
        val verb = if (task == GoPackages.COMPILE) "build" else "test"
        CliCommand("go $verb", listOf("go", verb) + packages.map { if (it == ".") "./..." else it })
    }
}
