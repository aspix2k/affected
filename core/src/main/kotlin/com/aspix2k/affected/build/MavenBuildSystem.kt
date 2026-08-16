package com.aspix2k.affected.build

import com.aspix2k.affected.AffectedRunSessions
import com.aspix2k.affected.AffectedSettings
import com.aspix2k.affected.OwnedProcessExecution
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.model.MavenPlugin
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

class MavenBuildSystem internal constructor(
    private val collectorFactory: ((Project) -> MavenCollectorRun?)?,
    private val onCollectorPublished: (MavenCollectorRun?) -> Unit,
    private val onLaunchQueued: () -> Unit,
) : SuspendingBuildSystem {

    constructor() : this(null, {}, {})

    internal constructor(onLaunchQueued: () -> Unit) : this(null, {}, onLaunchQueued)

    override val id: String = "MAVEN"

    override val sourceExtensions: Set<String> =
        JVM_SOURCE_EXTENSIONS + setOf("xml", "properties")

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
                testTask = data.testTask,
                compileTask = COMPILE_GOAL,
                hasTests = File(data.directory, "src/test").isDirectory,
                systemId = id,
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
        val testTask: String,
    )

    private fun describe(project: Project): List<Described> {
        val manager = MavenProjectsManager.getInstanceIfCreated(project) ?: return emptyList()
        val projects = manager.projects
        val roots = projects.associateWith { mavenProject ->
            rootOf(manager, File(mavenProject.directory).invariantSeparatorsPath)
        }
        val failsafeRoots = mavenFailsafeRoots(
            projects.filter { it.packaging != "pom" }.map { roots.getValue(it) to it.plugins },
        )
        return projects.map { mavenProject ->
            val directory = File(mavenProject.directory).invariantSeparatorsPath
            val root = roots.getValue(mavenProject)
            Described(
                mavenKey = mavenProject.mavenId.key,
                id = mavenProject.mavenId.artifactId ?: directory.substringAfterLast('/'),
                root = root,
                directory = directory,
                dependencies = mavenProject.dependencies.mapTo(HashSet()) { it.mavenId.key },
                testTask = mavenTestGoal(root in failsafeRoots),
            )
        }
    }

    private val MavenId.key: String get() = "$groupId:$artifactId"

    override fun run(project: Project, root: String, tasks: List<String>) {
        if (project.isDisposed) return
        val arguments = mavenInvocationArguments(
            emptyList(),
            AffectedSettings.getInstance().stopAfterFirstFailure,
        )
        MavenRunConfigurationType.runConfiguration(project, parameters(root, tasks, arguments), null)
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean {
        if (project.isDisposed) return false

        val collector = AtomicReference<MavenCollectorRun?>()
        val execution = OwnedProcessExecution { collector.get()?.cancel() }
        val sessions = AffectedRunSessions.getInstance(project)
        val launchQueued = AtomicBoolean()
        if (!sessions.register(execution)) {
            execution.finish()
            return false
        }
        try {
            val published = withContext(Dispatchers.IO) {
                (collectorFactory?.invoke(project) ?: collectorRun(project)).also { created ->
                    collector.set(created)
                    onCollectorPublished(created)
                }
            }
            if (execution.isCancellationRequested()) published?.cancel()
            val arguments = mavenInvocationArguments(
                published?.arguments.orEmpty(),
                AffectedSettings.getInstance().stopAfterFirstFailure,
            )
            val parameters = parameters(root, tasks, arguments)
            return awaitMavenRun(project, parameters, published, execution, launchQueued)
        } catch (cancelled: CancellationException) {
            execution.stopIfActive()
            if (launchQueued.get()) {
                withContext(NonCancellable) { execution.awaitFinished() }
            }
            throw cancelled
        } finally {
            if (!launchQueued.get()) {
                withContext(NonCancellable + Dispatchers.IO) {
                    execution.finish {
                        collector.get()?.complete(passed = false)
                    }
                }
            }
            sessions.unregister(execution)
        }
    }

    private suspend fun awaitMavenRun(
        project: Project,
        parameters: MavenRunnerParameters,
        collector: MavenCollectorRun?,
        execution: OwnedProcessExecution,
        launchQueued: AtomicBoolean,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)

        fun complete(passed: Boolean) {
            if (!completed.compareAndSet(false, true)) return
            CoroutineScope(Dispatchers.IO).launch {
                var cleaned = true
                val active = execution.finish { accepted ->
                    cleaned = runCatching { collector?.complete(passed && accepted) }.isSuccess
                }
                if (continuation.isActive) continuation.resume(passed && active && cleaned)
            }
        }

        continuation.invokeOnCancellation { execution.stopIfActive() }
        launchQueued.set(true)
        val scheduled = runCatching {
            ApplicationManager.getApplication().invokeLater {
                startMavenRun(project, parameters, execution, continuation.isActive, ::complete)
            }
        }.isSuccess
        if (scheduled) {
            onLaunchQueued()
        } else {
            launchQueued.set(false)
            complete(false)
        }
    }

    private fun startMavenRun(
        project: Project,
        parameters: MavenRunnerParameters,
        execution: OwnedProcessExecution,
        continuationActive: Boolean,
        complete: (Boolean) -> Unit,
    ) {
        if (!continuationActive || project.isDisposed || execution.isCancellationRequested()) {
            return complete(false)
        }
        runCatching {
            MavenRunConfigurationType.runConfiguration(
                project,
                parameters,
                object : ProgramRunner.Callback {
                    override fun processStarted(descriptor: RunContentDescriptor) {
                        val handler = descriptor.processHandler ?: return complete(false)
                        execution.bind(handler)
                        handler.addProcessListener(object : ProcessListener {
                            override fun processTerminated(event: ProcessEvent) = complete(event.exitCode == 0)
                        })
                        if (handler.isProcessTerminated) complete(handler.exitCode == 0)
                    }

                    override fun processNotStarted(error: Throwable?) = complete(false)
                },
            )
        }.onFailure { complete(false) }
    }

    private fun parameters(
        root: String,
        tasks: List<String>,
        options: List<String> = emptyList(),
    ): MavenRunnerParameters {
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
            if (options.isNotEmpty()) cmdOptions = ParametersListUtil.join(options)
        }
    }

    private fun collectorRun(project: Project): MavenCollectorRun? {
        val classPath = Path.of(PathManager.getJarPathForClass(MavenBuildSystem::class.java))
        val artifacts = findMavenCollectorArtifacts(classPath) ?: return null
        val cache = PathManager.getSystemDir().resolve(CACHE_DIRECTORY).resolve(project.locationHash).resolve("maven")
        return MavenCollectorRun.create(cache, artifacts)
    }

    private fun rootOf(manager: MavenProjectsManager, directory: String): String =
        manager.rootProjects.map { it.directory }
            .filter { directory == it || directory.startsWith("$it/") }
            .maxByOrNull { it.length }
            ?: directory

    private companion object {
        const val COMPILE_GOAL = "test-compile"
        const val CACHE_DIRECTORY = "affected"
    }
}

internal fun hasFailsafeIntegrationTests(plugins: List<MavenPlugin>): Boolean = plugins.any { plugin ->
    (plugin.groupId.isNullOrEmpty() || plugin.groupId == "org.apache.maven.plugins") &&
        plugin.artifactId == "maven-failsafe-plugin" &&
        plugin.executions.any { "integration-test" in it.goals }
}

internal fun mavenTestGoal(hasFailsafeInReactor: Boolean): String =
    if (hasFailsafeInReactor) "verify" else "test"

internal fun mavenFailsafeRoots(projects: List<Pair<String, List<MavenPlugin>>>): Set<String> = projects
    .filter { (_, plugins) -> hasFailsafeIntegrationTests(plugins) }
    .mapTo(HashSet()) { (root, _) -> root }
