package com.aspix2k.affected.build.go

import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.EngineBuildSystem
import com.aspix2k.affected.build.ManifestSearch
import com.aspix2k.affected.build.Workspace
import com.aspix2k.affected.build.capture
import com.aspix2k.affected.build.failClosedModules
import com.aspix2k.affected.build.isNeverMember
import com.aspix2k.affected.build.isRegularFileNoFollow
import com.aspix2k.affected.build.nestedBuildRoots
import com.aspix2k.affected.build.pathSegments
import com.aspix2k.affected.build.process.CliCommand
import com.aspix2k.affected.build.retainBuildSnapshot
import com.aspix2k.affected.build.runBatch
import com.aspix2k.affected.build.runBatchAndWait
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal class GoBuildSystem : EngineBuildSystem {

    private data class Snapshot(val stamp: String, val modules: List<BuildModule>)

    private val cache = ConcurrentHashMap<String, Snapshot>()

    override val id: String = "GO"

    override fun isTestSource(path: String): Boolean =
        pathSegments(path).last().lowercase().endsWith("_test.go")

    override val sourceExtensions: Set<String> = setOf("go", "mod", "sum", "work")

    override fun isPresent(workspace: Workspace): Boolean = rootsOf(workspace).isNotEmpty()

    override fun modules(workspace: Workspace): List<BuildModule> {
        val roots = rootsOf(workspace)
        cache.keys.retainAll(roots.mapTo(HashSet()) { it.invariantSeparatorsPath })
        return roots.flatMap { modulesOf(workspace, it, roots) }
    }

    private fun modulesOf(workspace: Workspace, directory: File, roots: List<File>): List<BuildModule> {
        val root = directory.invariantSeparatorsPath
        val nested = roots.map { it.invariantSeparatorsPath }.filter { it != root && it.startsWith("$root/") }
        val sources = ManifestSearch.findByExtension(directory, "go")
        val inputs = ManifestSearch.find(directory, setOf("go.mod", "go.sum", "go.work", "go.work.sum")) + sources
        val stamp = sources.takeIf { it.isNotEmpty() }?.let {
            ManifestSearch.fingerprint(directory, inputs)
        }

        if (stamp != null) cache[root]?.takeIf { it.stamp == stamp }?.let { return it.modules }

        val output = workspace.capture(root, LIST, timeoutSeconds = 120)
        val discovery = failClosedModules(
            directory,
            GoPackages.TEST,
            GoPackages.COMPILE,
            output?.let { GoPackages.parse(it, root).filterNot { module -> module.isWithin(nested) } },
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

    override fun run(workspace: Workspace, root: String, tasks: List<String>) {
        workspace.runBatch(root, goCommands(tasks), "Affected Go")
    }

    override suspend fun runAndWait(workspace: Workspace, root: String, tasks: List<String>): Boolean =
        workspace.runBatchAndWait(root, goCommands(tasks), "Affected Go")

    private fun rootsOf(workspace: Workspace): List<File> =
        workspace.root?.let(::goProjectRoots).orEmpty()

    private companion object {
        val LIST = listOf("go", "list", "-json", "./...")
    }
}

private fun BuildModule.isWithin(roots: List<String>): Boolean =
    contentRoots.any { directory -> roots.any { directory == it || directory.startsWith("$it/") } }

internal fun goProjectRoots(base: File): List<File> =
    nestedBuildRoots(base, setOf("go.mod"), isNeverMember) { goManifest(it) != null }

internal fun goManifest(root: File): File? =
    File(root, "go.mod").takeIf(File::isRegularFileNoFollow)

internal fun goCommands(tasks: List<String>): List<CliCommand> {
    val grouped = tasks.groupBy({ it.substringAfterLast(':') }, { it.substringBeforeLast(':') })
    return grouped.map { (task, packages) ->
        val verb = if (task == GoPackages.COMPILE) "build" else "test"
        CliCommand("go $verb", listOf("go", verb) + packages.map { if (it == ".") "./..." else it })
    }
}
