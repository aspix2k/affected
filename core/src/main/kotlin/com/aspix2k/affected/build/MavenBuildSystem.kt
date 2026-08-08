package com.aspix2k.affected.build

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class MavenBuildSystem : SuspendingBuildSystem {

    override val id: String = "MAVEN"

    override val sourceExtensions: Set<String> = setOf("java", "kt", "xml", "properties")

    override fun isPresent(project: Project): Boolean =
        MavenProjectsManager.getInstanceIfCreated(project)?.isMavenizedProject == true

    override fun modules(project: Project): List<BuildModule> =
        runBlockingCancellable { modulesSuspending(project) }

    override suspend fun modulesSuspending(project: Project): List<BuildModule> =
        modules(readAction { describe(project) })

    private fun modules(described: List<Described>): List<BuildModule> {
        val modules = described.associate { data ->
            data.mavenKey to BuildModule(
                id = data.id,
                root = data.root,
                contentRoots = listOf(data.directory),
                testTask = TEST_GOAL,
                compileTask = COMPILE_GOAL,
                hasTests = File(data.directory, "src/test").isDirectory,
            )
        }
        return described.map { data ->
            val module = modules.getValue(data.mavenKey)
            val dependencies = data.dependencies.mapNotNullTo(HashSet()) { modules[it]?.key }
            module.copy(dependencies = dependencies - module.key)
        }
    }

    private data class Described(
        val mavenKey: String,
        val id: String,
        val root: String,
        val directory: String,
        val dependencies: Set<String>,
    )

    private fun describe(project: Project): List<Described> {
        val manager = MavenProjectsManager.getInstanceIfCreated(project) ?: return emptyList()
        return manager.projects.map { mavenProject ->
            val directory = File(mavenProject.directory).invariantSeparatorsPath
            Described(
                mavenKey = mavenProject.mavenId.key,
                id = mavenProject.mavenId.artifactId ?: directory.substringAfterLast('/'),
                root = rootOf(manager, directory),
                directory = directory,
                dependencies = mavenProject.dependencies.mapTo(HashSet()) { it.mavenId.key },
            )
        }
    }

    private val MavenId.key: String get() = "$groupId:$artifactId"

    override fun run(project: Project, root: String, tasks: List<String>) {
        if (project.isDisposed) return
        MavenRunConfigurationType.runConfiguration(project, parameters(root, tasks), null)
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean {
        if (project.isDisposed) return false

        val parameters = parameters(root, tasks)
        return suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)

            fun complete(passed: Boolean) {
                if (completed.compareAndSet(false, true) && continuation.isActive) continuation.resume(passed)
            }

            ApplicationManager.getApplication().invokeLater {
                if (!continuation.isActive || project.isDisposed) return@invokeLater complete(false)
                MavenRunConfigurationType.runConfiguration(
                    project,
                    parameters,
                    object : ProgramRunner.Callback {
                        override fun processStarted(descriptor: RunContentDescriptor) {
                            val handler = descriptor.processHandler ?: return complete(false)
                            continuation.invokeOnCancellation {
                                if (!handler.isProcessTerminated) handler.destroyProcess()
                            }
                            handler.addProcessListener(object : ProcessListener {
                                override fun processTerminated(event: ProcessEvent) = complete(event.exitCode == 0)
                            })
                            if (handler.isProcessTerminated) complete(handler.exitCode == 0)
                        }

                        override fun processNotStarted(error: Throwable?) = complete(false)
                    },
                )
            }
        }
    }

    private fun parameters(root: String, tasks: List<String>): MavenRunnerParameters {
        val goals = tasks.map { it.substringAfterLast(':') }.distinct()
        val projects = tasks.mapNotNull { it.substringBeforeLast(':').takeIf(String::isNotBlank) }.distinct()
        return MavenRunnerParameters(
            true,
            root,
            null as String?,
            goals,
            emptyList(),
        ).apply {
            if (projects.isNotEmpty()) projectsCmdOptionValues = projects
        }
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
