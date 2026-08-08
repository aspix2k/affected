package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.io.File

class MavenBuildSystem : BuildSystem {

    override val id: String = "MAVEN"

    override val sourceExtensions: Set<String> = setOf("java", "kt", "xml", "properties")

    override fun isPresent(project: Project): Boolean =
        MavenProjectsManager.getInstanceIfCreated(project)?.isMavenizedProject == true

    override fun modules(project: Project): List<BuildModule> {
        val manager = MavenProjectsManager.getInstanceIfCreated(project) ?: return emptyList()

        val modules = manager.projects.associate { mavenProject ->
            val directory = File(mavenProject.directory).invariantSeparatorsPath
            val module = BuildModule(
                id = mavenProject.mavenId.artifactId ?: directory.substringAfterLast('/'),
                root = rootOf(manager, directory),
                contentRoots = listOf(directory),
                testTask = TEST_GOAL,
                compileTask = COMPILE_GOAL,
                hasTests = File(directory, "src/test").isDirectory,
            )
            mavenProject.mavenId.key to module
        }

        return modules.values.map { module ->
            val mavenProject = manager.projects.first { it.directory == module.contentRoots.single() }
            val dependencies = mavenProject.dependencies
                .mapNotNullTo(HashSet()) { modules[it.mavenId.key]?.key }
            module.copy(dependencies = dependencies - module.key)
        }
    }

    private val MavenId.key: String get() = "$groupId:$artifactId"

    override fun run(project: Project, root: String, tasks: List<String>) {
        val goals = tasks.map { it.substringAfterLast(':') }.distinct()
        val projects = tasks.mapNotNull { it.substringBeforeLast(':').takeIf(String::isNotBlank) }.distinct()

        val parameters = MavenRunnerParameters(
            true,
            root,
            null as String?,
            goals,
            emptyList(),
        )
        if (projects.isNotEmpty()) {
            parameters.projectsCmdOptionValues = projects
        }

        MavenRunConfigurationType.runConfiguration(project, parameters, null)
    }

    private fun rootOf(manager: MavenProjectsManager, directory: String): String =
        manager.rootProjects.map { it.directory }
            .filter { directory == it || directory.startsWith("$it/") }
            .maxByOrNull { it.length }
            ?: directory

    private companion object {
        const val TEST_GOAL = "test"
        const val COMPILE_GOAL = "test-compile"
    }
}
