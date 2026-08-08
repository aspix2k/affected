package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class CargoBuildSystem : SuspendingBuildSystem {

    private data class Snapshot(val stamp: Long, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "CARGO"

    override val sourceExtensions: Set<String> = setOf("rs", "toml")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val manifest = manifestOf(project) ?: return emptyList()
        val root = manifest.parentFile.invariantSeparatorsPath
        val stamp = manifest.lastModified()

        cache.get()?.takeIf { it.stamp == stamp }?.let { return it.modules }

        val output = CommandRunner.capture(root, METADATA) ?: return emptyList()
        val modules = CargoMetadata.parse(output, root)
        cache.set(Snapshot(stamp, modules))
        return modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        commands(tasks).forEach { (title, command) -> CommandRunner.run(project, root, command, title) }
    }

    private fun commands(tasks: List<String>): List<Pair<String, List<String>>> =
        tasks.groupBy({ it.substringAfterLast(':') }, { it.substringBeforeLast(':') })
            .map { (task, packages) ->
                val arguments = if (task == CargoMetadata.COMPILE) listOf("check", "--tests") else listOf("test")
                "cargo ${arguments.first()}" to listOf("cargo") + arguments + packages.flatMap { listOf("-p", it) }
            }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        commands(tasks).all { (title, command) -> CommandRunner.runAndWait(project, root, command, title) }

    private fun manifestOf(project: Project): File? =
        project.basePath?.let { File(it, "Cargo.toml") }?.takeIf { it.isFile }

    private companion object {
        val METADATA = listOf("cargo", "metadata", "--no-deps", "--format-version", "1")
    }
}
