package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class CargoBuildSystem : SuspendingBuildSystem {

    private data class Snapshot(val root: String, val stamp: String, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "CARGO"

    override val sourceExtensions: Set<String> = setOf("rs", "toml", "lock")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val manifest = manifestOf(project) ?: return emptyList()
        val root = manifest.parentFile.invariantSeparatorsPath
        val manifests = ManifestSearch.find(manifest.parentFile, "Cargo.toml")
        val inputs = manifests + listOf(File(manifest.parentFile, "Cargo.lock")).filter(File::isFile)
        val stamp = ManifestSearch.fingerprint(manifest.parentFile, inputs)

        if (stamp != null) cache.get()?.takeIf { it.root == root && it.stamp == stamp }?.let { return it.modules }

        val output = CommandRunner.capture(root, METADATA)
        val discovery = failClosedModules(
            manifest.parentFile,
            CargoMetadata.TEST,
            CargoMetadata.COMPILE,
            output?.let { CargoMetadata.parse(it, root) },
        )
        val discoveredManifests = discovery.modules.mapTo(HashSet()) { module ->
            File(module.contentRoots.single(), "Cargo.toml").absoluteFile.normalize().invariantSeparatorsPath
        }
        val fingerprintedManifests = manifests.mapTo(HashSet()) {
            it.absoluteFile.normalize().invariantSeparatorsPath
        }
        if (stamp != null && discovery.complete && fingerprintedManifests.containsAll(discoveredManifests)) {
            cache.set(Snapshot(root, stamp, discovery.modules))
        }
        return discovery.modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, cargoCommands(tasks), "Affected Cargo")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, cargoCommands(tasks), "Affected Cargo")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let { File(it, "Cargo.toml") }?.takeIf(File::isRegularFileNoFollow)

    private companion object {
        val METADATA = listOf("cargo", "metadata", "--no-deps", "--format-version", "1")
    }
}

internal fun cargoCommands(tasks: List<String>): List<CliCommand> =
    tasks.groupBy({ it.substringAfterLast(':') }, { it.substringBeforeLast(':') })
        .map { (task, packages) ->
            val arguments = if (task == CargoMetadata.COMPILE) listOf("check", "--tests") else listOf("test")
            val selection = if ("." in packages) listOf("--workspace") else packages.flatMap { listOf("-p", it) }
            CliCommand("cargo ${arguments.first()}", listOf("cargo") + arguments + selection)
        }
