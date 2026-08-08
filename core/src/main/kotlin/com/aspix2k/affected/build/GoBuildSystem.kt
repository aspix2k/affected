package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class GoBuildSystem : SuspendingBuildSystem {

    private data class Snapshot(val stamp: Long, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "GO"

    override val sourceExtensions: Set<String> = setOf("go", "mod", "sum")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val manifest = manifestOf(project) ?: return emptyList()
        val root = manifest.parentFile.invariantSeparatorsPath
        val stamp = manifest.lastModified()

        cache.get()?.takeIf { it.stamp == stamp }?.let { return it.modules }

        val output = CommandRunner.capture(root, LIST, timeoutSeconds = 120) ?: return emptyList()
        val modules = GoPackages.parse(output, root)
        cache.set(Snapshot(stamp, modules))
        return modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        commands(tasks).forEach { (title, command) -> CommandRunner.run(project, root, command, title) }
    }

    private fun commands(tasks: List<String>): List<Pair<String, List<String>>> =
        tasks.groupBy({ it.substringAfterLast(':') }, { it.substringBeforeLast(':') })
            .map { (task, packages) ->
                val verb = if (task == GoPackages.COMPILE) "build" else "test"
                "go $verb" to listOf("go", verb) + packages
            }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        commands(tasks).all { (title, command) -> CommandRunner.runAndWait(project, root, command, title) }

    private fun manifestOf(project: Project): File? =
        project.basePath?.let { File(it, "go.mod") }?.takeIf { it.isFile }

    private companion object {
        val LIST = listOf("go", "list", "-json", "./...")
    }
}
