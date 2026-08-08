package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class ComposerBuildSystem : SuspendingBuildSystem {

    private data class Snapshot(val root: String, val stamp: Long, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "COMPOSER"

    override val sourceExtensions: Set<String> = setOf("php", "json", "neon", "xml")

    override fun isPresent(project: Project): Boolean = rootOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = rootOf(project) ?: return emptyList()
        val stamp = File(root, "composer.json").lastModified()

        val rootPath = root.invariantSeparatorsPath
        cache.get()?.takeIf { it.root == rootPath && it.stamp == stamp }?.let { return it.modules }

        val modules = ComposerPackages.parse(root)
        cache.set(Snapshot(rootPath, stamp, modules))
        return modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        commands(project, root, tasks).forEach { (title, command) ->
            CommandRunner.run(project, root, command, title)
        }
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        commands(project, root, tasks).all { (title, command) ->
            CommandRunner.runAndWait(project, root, command, title)
        }

    private fun commands(project: Project, root: String, tasks: List<String>): List<Pair<String, List<String>>> {
        val byName = modules(project).associateBy { it.id }

        return tasks.mapNotNull { task ->
            val name = task.substringBeforeLast(':')
            val directory = byName[name]?.contentRoots?.singleOrNull() ?: return@mapNotNull null
            val relative = directory.removePrefix("$root/").ifEmpty { "." }

            if (task.substringAfterLast(':') == ComposerPackages.ANALYSE) {
                "phpstan $name" to listOf("php", "vendor/bin/phpstan", "analyse", relative)
            } else {
                "phpunit $name" to listOf("php", "vendor/bin/phpunit", relative)
            }
        }
    }

    private fun rootOf(project: Project): File? =
        project.basePath?.let(::File)?.takeIf { File(it, "composer.json").isFile }
}
