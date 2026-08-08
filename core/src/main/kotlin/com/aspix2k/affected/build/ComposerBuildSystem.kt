package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class ComposerBuildSystem : BuildSystem {

    private data class Snapshot(val stamp: Long, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "COMPOSER"

    override val sourceExtensions: Set<String> = setOf("php", "json", "neon", "xml")

    override fun isPresent(project: Project): Boolean = rootOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = rootOf(project) ?: return emptyList()
        val stamp = File(root, "composer.json").lastModified()

        cache.get()?.takeIf { it.stamp == stamp }?.let { return it.modules }

        val modules = ComposerPackages.parse(root)
        cache.set(Snapshot(stamp, modules))
        return modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        commands(project, root, tasks).forEach { (title, command) ->
            CommandRunner.run(project, root, command, title)
        }
    }

    override fun runAndWait(project: Project, root: String, tasks: List<String>): Boolean =
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

    private fun rootOf(project: Project): File? {
        val base = project.basePath?.let(::File) ?: return null
        if (!File(base, "composer.json").isFile) return null
        return base.takeIf { ComposerPackages.parse(it).isNotEmpty() }
    }
}
