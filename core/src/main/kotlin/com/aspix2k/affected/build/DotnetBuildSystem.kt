package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class DotnetBuildSystem : SuspendingBuildSystem {

    override val id: String = "DOTNET"

    override val sourceExtensions: Set<String> = setOf("cs", "fs", "vb", "csproj", "fsproj", "vbproj", "props", "razor")

    override fun isPresent(project: Project): Boolean = rootOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = rootOf(project) ?: return emptyList()
        return DotnetProjects.parse(root)
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

    private fun commands(
        project: Project,
        root: String,
        tasks: List<String>,
    ): List<Pair<String, List<String>>> {
        val byName = modules(project).associateBy { it.id }

        return tasks.mapNotNull { task ->
            val name = task.substringBeforeLast(':')
            val verb = if (task.substringAfterLast(':') == DotnetProjects.COMPILE) "build" else "test"
            val directory = byName[name]?.contentRoots?.singleOrNull() ?: return@mapNotNull null
            val relative = directory.removePrefix("$root/")

            "dotnet $verb $name" to listOf("dotnet", verb, relative)
        }
    }

    private fun rootOf(project: Project): File? {
        val base = project.basePath?.let(::File) ?: return null
        val children = base.listFiles() ?: return null
        return base.takeIf { _ ->
            children.any {
                it.extension.lowercase() == "sln" ||
                    it.name == "global.json" ||
                    DotnetProjects.isProjectFile(it)
            }
        }
    }
}
