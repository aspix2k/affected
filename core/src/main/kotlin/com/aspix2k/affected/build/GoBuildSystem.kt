package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class GoBuildSystem : SuspendingBuildSystem {

    private data class Snapshot(val root: String, val stamp: String, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "GO"

    override val sourceExtensions: Set<String> = setOf("go", "mod", "sum", "work")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val manifest = manifestOf(project) ?: return emptyList()
        val root = manifest.parentFile.invariantSeparatorsPath
        val sources = ManifestSearch.findByExtension(manifest.parentFile, "go")
        val inputs =
            listOf("go.mod", "go.sum", "go.work", "go.work.sum")
                .flatMap { ManifestSearch.find(manifest.parentFile, it) } +
                sources
        val stamp = sources.takeIf { it.isNotEmpty() }?.let {
            ManifestSearch.fingerprint(manifest.parentFile, inputs)
        }

        if (stamp != null) cache.get()?.takeIf { it.root == root && it.stamp == stamp }?.let { return it.modules }

        val output = CommandRunner.capture(root, LIST, timeoutSeconds = 120)
        val discovery = failClosedModules(
            manifest.parentFile,
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
            cache.retainBuildSnapshot(Snapshot(root, stamp, discovery.modules), discovery.modules.size)
        }
        return discovery.modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, goCommands(tasks), "Affected Go")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, goCommands(tasks), "Affected Go")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let(::File)?.let(::goProjectRoot)?.let(::goManifest)

    private companion object {
        val LIST = listOf("go", "list", "-json", "./...")
    }
}

internal fun goProjectRoot(base: File): File? =
    nestedBuildRoot(base) { goManifest(it) != null }

internal fun goManifest(root: File): File? =
    File(root, "go.mod").takeIf(File::isRegularFileNoFollow)

internal fun goCommands(tasks: List<String>): List<CliCommand> {
    val grouped = tasks.groupBy({ it.substringAfterLast(':') }, { it.substringBeforeLast(':') })
    return grouped.map { (task, packages) ->
        val verb = if (task == GoPackages.COMPILE) "build" else "test"
        CliCommand("go $verb", listOf("go", verb) + packages.map { if (it == ".") "./..." else it })
    }
}
