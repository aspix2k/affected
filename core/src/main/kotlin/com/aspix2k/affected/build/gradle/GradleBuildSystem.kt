package com.aspix2k.affected.build.gradle

import com.aspix2k.affected.AffectedExternalRunBinding
import com.aspix2k.affected.AffectedRunSessions
import com.aspix2k.affected.AffectedSettings
import com.aspix2k.affected.BaseCheckRun
import com.aspix2k.affected.OutputMarker
import com.aspix2k.affected.OwnedExternalTaskExecution
import com.aspix2k.affected.affectedRunLabel
import com.aspix2k.affected.build.BaseRuntimeBuildSystem
import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.ChangeAwareSuspendingBuildSystem
import com.aspix2k.affected.build.WorkspaceChangesBuildSystem
import com.aspix2k.affected.build.gradleInvocationArguments
import com.aspix2k.affected.build.isJvmTestSourceSet
import com.aspix2k.affected.build.moduleDependencyKey
import com.aspix2k.affected.build.requiredGradleFailureStrategyScript
import com.aspix2k.affected.build.rootFallbackModule
import com.aspix2k.affected.currentAffectedRunPresentation
import com.aspix2k.affected.gradleTaskReached
import com.aspix2k.affected.monitorGradleCancellation
import com.aspix2k.affected.recordsDependencies
import com.aspix2k.affected.runPreparedOwnedExternalTask
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.externalSystem.service.project.ExternalSystemModuleDataIndex
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.externalSystem.util.task.TaskExecutionSpec
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.util.execution.ParametersListUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.gradle.service.GradleInstallationManager
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

class GradleBuildSystem : ChangeAwareSuspendingBuildSystem, WorkspaceChangesBuildSystem, BaseRuntimeBuildSystem {

    override fun sameRuntime(project: Project, root: String, baseRoot: String): Boolean {
        val linked = GradleSettings.getInstance(project).linkedProjectsSettings
            .map { File(it.externalProjectPath).invariantSeparatorsPath }
            .filter { root == it || root.startsWith("$it/") }
            .maxByOrNull(String::length)
            ?: return false
        val installations = GradleInstallationManager.getInstance()
        val jvm = installations.getGradleJvmPath(project, linked)
        return !jvm.isNullOrEmpty() && jvm == installations.getGradleJvmPath(project, baseRoot)
    }

    override val id: String = GradleConstants.SYSTEM_ID.id

    override fun isTestSource(path: String): Boolean = isJvmTestSourceSet(path)

    override val consumersNeedSignatureChange: Boolean = true

    override val singleOwnerPerRoot: Boolean = true

    override val sourceExtensions: Set<String> =
        JVM_SOURCE_EXTENSIONS + setOf("gradle", "kts", "properties", "toml", "xml", "json", "pro")

    override fun isPresent(project: Project): Boolean =
        GradleSettings.getInstance(project).linkedProjectsSettings.isNotEmpty()

    override fun requiresWorkspace(module: BuildModule, changes: BuildChanges): Boolean =
        gradleRequiresWorkspace(module.root, changes) || gradleUnverifiableScriptChanged(module, changes)

    override fun consumerRoots(root: String, candidateRoots: Set<String>): Set<String> =
        gradleConsumerRoots(root, candidateRoots)

    override fun modules(project: Project): List<BuildModule> =
        runBlockingCancellable { modulesSuspending(project) }

    override suspend fun modulesSuspending(project: Project): List<BuildModule> =
        modules(project, readAction { snapshot(project) })

    private fun modules(project: Project, snapshot: Snapshot): List<BuildModule> {
        if (snapshot.modules.isEmpty() && snapshot.linkedRoots.isNotEmpty()) {
            return snapshot.linkedRoots.map { linked ->
                rootFallbackModule(File(linked), "", null).copy(hasTests = false)
            }
        }
        val built = snapshot.modules.groupBy(Described::key).mapValues { (_, descriptions) ->
            val first = descriptions.first()
            val roots = descriptions.flatMap(Described::roots).distinct()
            val testRoots = descriptions.flatMap(Described::testRoots).distinct()
            val dependencies = descriptions.flatMapTo(HashSet(), Described::dependencies) - first.key
            val ownerRoot = buildRootOf(File(first.projectPath), project)
            val linkedRoot = gradleCompositeRoot(ownerRoot, snapshot.linkedRoots, first.buildName)
            val execution = gradleExecutionCoordinates(
                ownerRoot,
                first.path,
                first.directoryToRunTask,
                first.identityPath,
                linkedRoot,
                first.buildName,
            )

            gradleModule(
                first.path,
                first.projectPath,
                ownerRoot,
                roots,
                testRoots,
                snapshot.tasks,
                execution,
            ) to dependencies
        }
        return gradleModulesWithDependencies(built)
    }

    private data class Snapshot(
        val tasks: GradleTaskModel,
        val modules: List<Described>,
        val linkedRoots: List<String>,
    )

    private fun snapshot(project: Project): Snapshot {
        val modules = ModuleManager.getInstance(project).modules
        val buildNames = modules.mapNotNull { module ->
            val projectPath = ExternalSystemApiUtil.getExternalProjectPath(module) ?: return@mapNotNull null
            val buildName = ExternalSystemApiUtil.getExternalProjectGroup(module) ?: return@mapNotNull null
            projectPath to buildName
        }.toMap()
        val described = modules.mapNotNull { module -> describe(module, buildNames)?.let { module to it } }
        val keyByModule = described.associate { (module, data) -> module to data.key }
        val keyByRoot = described.flatMap { (_, data) -> data.roots.map { it to data.key } }.toMap()
        val withDependencies = described.map { (module, data) ->
            val dependencies = ModuleRootManager.getInstance(module).dependencies
                .mapNotNullTo(HashSet()) { dependency ->
                    keyByModule[dependency]
                        ?: ModuleRootManager.getInstance(dependency).contentRoots
                            .firstNotNullOfOrNull { keyByRoot[it.path] }
                }
            data.copy(dependencies = dependencies)
        }
        val linkedRoots = GradleSettings.getInstance(project).linkedProjectsSettings
            .map { File(it.externalProjectPath).invariantSeparatorsPath }
        return Snapshot(tasksByDirectory(project), withDependencies, linkedRoots)
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        runAndWaitSuspending(project, root, tasks, BuildChanges(emptyList(), emptySet(), comparedToBase = false))

    override suspend fun runAndWaitSuspending(
        project: Project,
        root: String,
        tasks: List<String>,
        changes: BuildChanges,
    ): Boolean {
        if (project.isDisposed) return false
        val stopAfterFirstFailure = AffectedSettings.getInstance().stopAfterFirstFailure
        val failureStrategyScript = if (stopAfterFirstFailure) {
            requiredGradleFailureStrategyScript(Path.of(PathManager.getJarPathForClass(GradleBuildSystem::class.java)))
        } else {
            null
        }
        val presentation = currentAffectedRunPresentation()
        if (presentation != null && !AffectedExternalRunBinding.isSupported()) return false
        val recordsDependencies = recordsDependencies()
        var binding: AffectedExternalRunBinding? = null
        val sessions = AffectedRunSessions.getInstance(project)
        val collector = AtomicReference<GradleCollectorRun?>()
        val baseRun = currentCoroutineContext()[BaseCheckRun]
        val reached = baseRun?.let { OutputMarker(gradleTaskReached(tasks)) }
        val execution = OwnedExternalTaskExecution(
            cancelTask = { id, onTerminated, onMonitoringStopped, onCancelAttemptsExhausted ->
                cancelExternalTask(id, onTerminated, onMonitoringStopped, onCancelAttemptsExhausted)
            },
            onCancel = { collector.get()?.cancel() },
            onOutput = { reached?.accept(it) },
        )
        lateinit var settings: ExternalSystemTaskExecutionSettings
        var passed = false
        try {
            passed = runPreparedOwnedExternalTask(
                sessions = sessions,
                execution = execution,
                prepare = {
                    val preparedCollector = publishGradleCollector(collector) {
                        if (recordsDependencies) collectorRun(project) else null
                    }
                    if (execution.isCancellationRequested()) preparedCollector?.cancel()
                    val selection = withContext(Dispatchers.IO) { gradleTaskSelection(tasks, changes) }
                    binding = presentation?.let {
                        checkNotNull(
                            AffectedExternalRunBinding.open(
                                project,
                                it,
                                affectedRunLabel("Gradle", root, project.basePath),
                                initialOutput = selection.diagnosticOutput,
                                matches = AffectedExternalRunBinding::matchesMarker,
                            ),
                        )
                    }
                    val arguments = gradleInvocationArguments(
                        preparedCollector?.arguments.orEmpty(),
                        stopAfterFirstFailure,
                        failureStrategyScript,
                    )
                    settings = gradleTaskExecutionSettings(root, selection.taskNames, arguments)
                },
                launch = { listener ->
                    ExternalSystemUtil.runTask(
                        gradleTaskExecutionSpec(
                            project,
                            settings,
                            listener,
                            execution.callback,
                            binding?.userData,
                        ),
                    )
                },
            )
            return passed
        } finally {
            baseRun?.record(reached)
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { collector.get()?.complete(passed) }
            }
            binding?.dispose()
        }
    }

    private fun collectorRun(project: Project): GradleCollectorRun? {
        val classPath = Path.of(PathManager.getJarPathForClass(GradleBuildSystem::class.java))
        val artifacts = findGradleCollectorArtifacts(classPath) ?: return null
        val cache = PathManager.getSystemDir().resolve(CACHE_DIRECTORY).resolve(project.locationHash).resolve("gradle")
        return GradleCollectorRun.create(cache, artifacts)
    }

    private data class Described(
        val key: String,
        val path: String,
        val projectPath: String,
        val roots: List<String>,
        val testRoots: List<String>,
        val directoryToRunTask: String?,
        val identityPath: String?,
        val buildName: String?,
        val dependencies: Set<String> = emptySet(),
    )

    private fun describe(module: Module, buildNames: Map<String, String>): Described? {
        if (!ExternalSystemApiUtil.isExternalSystemAwareModule(GradleConstants.SYSTEM_ID, module)) return null

        val projectPath = ExternalSystemApiUtil.getExternalProjectPath(module) ?: return null
        val externalId = ExternalSystemApiUtil.getExternalProjectId(module) ?: return null
        val buildName = ExternalSystemApiUtil.getExternalProjectGroup(module) ?: buildNames[projectPath]
        val sourceSet = ExternalSystemApiUtil.getExternalModuleType(module) == SOURCE_SET_TYPE

        val path = gradleProjectPath(externalId, buildName, sourceSet)

        val rootManager = ModuleRootManager.getInstance(module)
        val roots = rootManager.contentRoots.map { it.path }
        if (roots.isEmpty()) return null
        val testRoots = rootManager.contentEntries
            .flatMap { it.sourceFolders.asList() }
            .filter { it.isTestSource }
            .mapNotNull { it.file?.path }
        val (directoryToRunTask, identityPath) = gradleExecutionMetadata(
            ExternalSystemModuleDataIndex.findModuleNode(module)?.data,
        )

        return Described(
            key = moduleDependencyKey(id, projectPath, path),
            path = path,
            projectPath = projectPath,
            roots = roots,
            testRoots = testRoots,
            directoryToRunTask = directoryToRunTask,
            identityPath = identityPath,
            buildName = buildName,
        )
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        if (project.isDisposed) return
        val stopAfterFirstFailure = AffectedSettings.getInstance().stopAfterFirstFailure
        val failureStrategyScript = if (stopAfterFirstFailure) {
            requiredGradleFailureStrategyScript(Path.of(PathManager.getJarPathForClass(GradleBuildSystem::class.java)))
        } else {
            null
        }
        val arguments = gradleInvocationArguments(
            emptyList(),
            stopAfterFirstFailure,
            failureStrategyScript,
        )

        val settings = gradleTaskExecutionSettings(root, tasks, arguments)
        ExternalSystemUtil.runTask(
            settings,
            DefaultRunExecutor.EXECUTOR_ID,
            project,
            GradleConstants.SYSTEM_ID,
            null,
            ProgressExecutionMode.IN_BACKGROUND_ASYNC,
        )
    }

    private fun buildRootOf(moduleDir: File, project: Project): String {
        var current: File? = moduleDir
        while (current != null) {
            if (SETTINGS_FILES.any { File(current, it).isFile }) return current.invariantSeparatorsPath
            current = current.parentFile
        }
        return project.basePath ?: moduleDir.invariantSeparatorsPath
    }

    private fun tasksByDirectory(project: Project): GradleTaskModel {
        val result = HashMap<String, MutableSet<String>>()
        val tests = HashMap<String, MutableSet<String>>()

        for (settings in GradleSettings.getInstance(project).linkedProjectsSettings) {
            val projectNode = ExternalSystemApiUtil.findProjectNode(
                project,
                GradleConstants.SYSTEM_ID,
                settings.externalProjectPath,
            ) ?: continue

            for (moduleNode in ExternalSystemApiUtil.findAll(projectNode, ProjectKeys.MODULE)) {
                val data: ModuleData = moduleNode.data
                val tasks = ExternalSystemApiUtil.findAll(moduleNode, ProjectKeys.TASK).map { it.data }
                val name = { task: TaskData -> task.name.substringAfterLast(':') }
                result.getOrPut(data.linkedExternalProjectPath) { mutableSetOf() } += tasks.map(name)
                tests.getOrPut(data.linkedExternalProjectPath) { mutableSetOf() } +=
                    tasks.filter(TaskData::isJvmTest).map(name)
            }
        }
        return GradleTaskModel(result, tests)
    }

    internal fun gradleTaskExecutionSettings(
        root: String,
        tasks: List<String>,
        arguments: List<String>,
    ): ExternalSystemTaskExecutionSettings = ExternalSystemTaskExecutionSettings().apply {
        executionName = "Affected"
        externalProjectPath = root
        taskNames = tasks
        externalSystemIdString = GradleConstants.SYSTEM_ID.id
        if (arguments.isNotEmpty()) scriptParameters = ParametersListUtil.join(arguments)
    }

    internal fun gradleTaskExecutionSpec(
        project: Project,
        settings: ExternalSystemTaskExecutionSettings,
        listener: ExternalSystemTaskNotificationListener,
        callback: TaskCallback,
        userData: UserDataHolderBase? = null,
    ): TaskExecutionSpec {
        val builder = TaskExecutionSpec.create()
            .withProject(project)
            .withSystemId(GradleConstants.SYSTEM_ID)
            .withExecutorId(DefaultRunExecutor.EXECUTOR_ID)
            .withSettings(settings)
            .withListener(listener)
            .withCallback(callback)
            .withProgressExecutionMode(ProgressExecutionMode.NO_PROGRESS_SYNC)
        if (userData != null) builder.withUserData(userData)
        return builder.build()
    }

    private companion object {
        const val SOURCE_SET_TYPE = "sourceSet"
        val SETTINGS_FILES = listOf("settings.gradle.kts", "settings.gradle")

        const val CACHE_DIRECTORY = "affected"
    }
}

internal suspend fun publishGradleCollector(
    target: AtomicReference<GradleCollectorRun?>,
    create: () -> GradleCollectorRun?,
): GradleCollectorRun? = withContext(Dispatchers.IO) {
    create().also(target::set)
}

internal fun cancelExternalTask(
    id: ExternalSystemTaskId,
    onTerminated: () -> Unit,
    onMonitoringStopped: () -> Unit,
    onCancelAttemptsExhausted: () -> Unit,
): Boolean {
    val manager = ExternalSystemProcessingManager.getInstance()
    val task = manager.findTask(id) ?: return false
    return monitorGradleCancellation(
        cancel = {
            ProgressManager.getInstance().runProcess(
                Computable { task.cancel(ExternalSystemTaskNotificationListener.NULL_OBJECT) },
                EmptyProgressIndicator(),
            )
        },
        terminated = { task.state.isStopped && manager.findTask(id) == null },
        onTerminated = onTerminated,
        onMonitoringStopped = onMonitoringStopped,
        onCancelAttemptsExhausted = onCancelAttemptsExhausted,
    )
}

internal fun findGradleCollectorArtifacts(classPath: Path): GradleCollectorArtifacts? {
    var directory = classPath.toAbsolutePath().normalize().let {
        if (Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)) it else it.parent
    } ?: return null
    repeat(MAX_PLUGIN_PARENT_DEPTH) {
        val artifacts = GradleCollectorArtifacts(
            directory.resolve(AGENT_PATH),
            directory.resolve(LISTENER_PATH),
            directory.resolve(INIT_SCRIPT_PATH),
        )
        if (listOf(artifacts.agent, artifacts.listener, artifacts.initScript).all {
                Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(it)
            }
        ) {
            return artifacts
        }
        directory = directory.parent ?: return null
    }
    return null
}

internal fun gradleProjectPath(externalId: String, buildName: String?, sourceSet: Boolean): String {
    val parts = externalId.removePrefix(":").split(':').toMutableList()
    if (parts.firstOrNull() == buildName) parts.removeFirst()
    if (sourceSet && parts.isNotEmpty()) parts.removeLast()
    return parts.joinToString(":", prefix = if (parts.isEmpty()) "" else ":")
}

internal fun gradleExecutionMetadata(moduleData: ModuleData?): Pair<String?, String?> {
    if (moduleData == null) return null to null
    val directory = moduleData.getProperty(DIRECTORY_TO_RUN_TASK_PROPERTY)
        ?: moduleData.linkedExternalProjectPath
    return directory to moduleData.getProperty(GRADLE_IDENTITY_PATH_PROPERTY)
}

internal fun gradleExecutionCoordinates(
    ownerRoot: String,
    ownerId: String,
    directoryToRunTask: String?,
    identityPath: String?,
    linkedRoot: String? = null,
    buildName: String? = null,
): Pair<String, String> {
    if (!directoryToRunTask.isNullOrBlank() && !identityPath.isNullOrBlank()) {
        return File(directoryToRunTask).invariantSeparatorsPath to identityPath.removeSuffix(":")
    }

    val fallbackRoot = linkedRoot?.takeUnless(String::isBlank) ?: return ownerRoot to ownerId
    val ownerPath = File(ownerRoot).toPath().toAbsolutePath().normalize()
    val fallbackPath = File(fallbackRoot).toPath().toAbsolutePath().normalize()
    if (ownerPath == fallbackPath || !ownerPath.startsWith(fallbackPath)) return ownerRoot to ownerId
    val fallbackId = when {
        !identityPath.isNullOrBlank() -> identityPath.removeSuffix(":")
        buildName.isNullOrBlank() -> return ownerRoot to ownerId
        else -> listOf(buildName.trim(':'), ownerId.trim(':'))
            .filter(String::isNotEmpty)
            .joinToString(":", prefix = ":")
    }
    return fallbackPath.toFile().invariantSeparatorsPath to fallbackId
}

internal fun gradleCompositeRoot(ownerRoot: String, linkedRoots: List<String>, buildName: String?): String? {
    if (buildName.isNullOrBlank()) return null
    val owner = File(ownerRoot).toPath().toAbsolutePath().normalize()
    val linked = linkedRoots.map { File(it).toPath().toAbsolutePath().normalize() }
    val root = linked.firstOrNull { it == owner }
        ?: linked.filter(owner::startsWith).maxByOrNull { it.nameCount }
    return root?.toFile()?.invariantSeparatorsPath
}

private const val DIRECTORY_TO_RUN_TASK_PROPERTY = "directoryToRunTask"
private const val GRADLE_IDENTITY_PATH_PROPERTY = "gradleIdentityPath"
private const val MAX_PLUGIN_PARENT_DEPTH = 5
private const val AGENT_PATH = "agent/affected-collector-agent.jar"
private const val LISTENER_PATH = "agent/affected-collector-listener.jar"
private const val INIT_SCRIPT_PATH = "agent/affected-collector.init.gradle"
