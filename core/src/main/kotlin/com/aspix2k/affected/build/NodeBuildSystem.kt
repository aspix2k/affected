package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class NodeBuildSystem : BuildSystem {

    private data class Snapshot(val stamp: Long, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "NODE"

    override val sourceExtensions: Set<String> = setOf("ts", "tsx", "js", "jsx", "mjs", "cjs", "json", "vue", "svelte")

    override fun isPresent(project: Project): Boolean = rootOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = rootOf(project) ?: return emptyList()
        val stamp = manifests(root).sumOf { it.lastModified() }

        cache.get()?.takeIf { it.stamp == stamp }?.let { return it.modules }

        val modules = NodeWorkspaces.parse(root)
        cache.set(Snapshot(stamp, modules))
        return modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        commands(root, tasks).forEach { (title, command) -> CommandRunner.run(project, root, command, title) }
    }

    override fun runAndWait(project: Project, root: String, tasks: List<String>): Boolean =
        commands(root, tasks).all { (title, command) -> CommandRunner.runAndWait(project, root, command, title) }

    private fun commands(root: String, tasks: List<String>): List<Pair<String, List<String>>> {
        val manager = managerOf(File(root))

        return tasks.groupBy({ it.substringAfterLast(':') }, { it.substringBeforeLast(':') })
            .flatMap { (task, packages) ->
                when (task) {
                    NodeWorkspaces.TEST -> packages.map { name ->
                        "$manager test $name" to testCommand(manager, name)
                    }
                    else -> packages.map { name ->
                        "typecheck $name" to typeCheckCommand(manager, name)
                    }
                }
            }
    }

    private fun testCommand(manager: String, name: String): List<String> = when (manager) {
        "pnpm" -> listOf("pnpm", "--filter", name, "test")
        "yarn" -> listOf("yarn", "workspace", name, "test")
        else -> listOf("npm", "test", "--workspace", name)
    }

    private fun typeCheckCommand(manager: String, name: String): List<String> = when (manager) {
        "pnpm" -> listOf("pnpm", "--filter", name, "exec", "tsc", "--noEmit")
        "yarn" -> listOf("yarn", "workspace", name, "exec", "tsc", "--noEmit")
        else -> listOf("npm", "exec", "--workspace", name, "--", "tsc", "--noEmit")
    }

    private fun managerOf(root: File): String = when {
        File(root, "pnpm-lock.yaml").isFile || File(root, "pnpm-workspace.yaml").isFile -> "pnpm"
        File(root, "yarn.lock").isFile -> "yarn"
        else -> "npm"
    }

    private fun manifests(root: File): List<File> = listOfNotNull(
        File(root, "package.json").takeIf { it.isFile },
        File(root, "pnpm-workspace.yaml").takeIf { it.isFile },
    )

    private fun rootOf(project: Project): File? =
        project.basePath?.let(::File)?.takeIf { File(it, "package.json").isFile }
}
