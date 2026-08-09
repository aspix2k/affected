package com.aspix2k.affected.build

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.readAction
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.plugins.gradle.service.project.GradleModuleDataIndex
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class GradleBuildSystem : SuspendingBuildSystem {

    override val id: String = GradleConstants.SYSTEM_ID.id

    override val sourceExtensions: Set<String> = setOf("kt", "kts", "java", "xml", "json", "pro")

    override fun isPresent(project: Project): Boolean =
        GradleSettings.getInstance(project).linkedProjectsSettings.isNotEmpty()

    override fun modules(project: Project): List<BuildModule> =
        runBlockingCancellable { modulesSuspending(project) }

    override suspend fun modulesSuspending(project: Project): List<BuildModule> =
        modules(project, readAction { snapshot(project) })

    private fun modules(project: Project, snapshot: Snapshot): List<BuildModule> {
        return snapshot.modules.groupBy(Described::key).values.map { descriptions ->
            val first = descriptions.first()
            val roots = descriptions.flatMap(Described::roots).distinct()
            val dependencies = descriptions.flatMapTo(HashSet(), Described::dependencies) - first.key
            val ownerRoot = buildRootOf(File(first.projectPath), project)
            val (executionRoot, executionId) = gradleExecutionCoordinates(
                ownerRoot,
                first.path,
                first.directoryToRunTask,
                first.identityPath,
            )

            build(
                first.path,
                first.projectPath,
                ownerRoot,
                roots,
                snapshot.tasks,
                executionRoot,
                executionId,
            ).copy(dependencies = dependencies)
        }
    }

    private data class Snapshot(
        val tasks: Map<String, Set<String>>,
        val modules: List<Described>,
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
        return Snapshot(tasksByDirectory(project), withDependencies)
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean {
        if (project.isDisposed) return false

        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = root
            taskNames = tasks
            externalSystemIdString = GradleConstants.SYSTEM_ID.id
        }

        return suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)

            fun complete(passed: Boolean) {
                if (completed.compareAndSet(false, true) && continuation.isActive) continuation.resume(passed)
            }

            ExternalSystemUtil.runTask(
                settings,
                DefaultRunExecutor.EXECUTOR_ID,
                project,
                GradleConstants.SYSTEM_ID,
                object : TaskCallback {
                    override fun onSuccess() = complete(true)

                    override fun onFailure() = complete(false)
                },
                ProgressExecutionMode.IN_BACKGROUND_ASYNC,
            )
        }
    }

    private data class Described(
        val key: String,
        val path: String,
        val projectPath: String,
        val roots: List<String>,
        val directoryToRunTask: String?,
        val identityPath: String?,
        val dependencies: Set<String> = emptySet(),
    )

    private fun describe(module: Module, buildNames: Map<String, String>): Described? {
        if (!ExternalSystemApiUtil.isExternalSystemAwareModule(GradleConstants.SYSTEM_ID, module)) return null

        val projectPath = ExternalSystemApiUtil.getExternalProjectPath(module) ?: return null
        val externalId = ExternalSystemApiUtil.getExternalProjectId(module) ?: return null
        val buildName = ExternalSystemApiUtil.getExternalProjectGroup(module) ?: buildNames[projectPath]
        val sourceSet = ExternalSystemApiUtil.getExternalModuleType(module) == SOURCE_SET_TYPE

        val path = gradleProjectPath(externalId, buildName, sourceSet)

        val roots = ModuleRootManager.getInstance(module).contentRoots.map { it.path }
        if (roots.isEmpty()) return null
        val gradleData = GradleModuleDataIndex.findGradleModuleData(module)

        return Described(
            key = "$projectPath|$path",
            path = path,
            projectPath = projectPath,
            roots = roots,
            directoryToRunTask = gradleData?.directoryToRunTask,
            identityPath = gradleData?.gradleIdentityPathOrNull,
        )
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        if (project.isDisposed) return

        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = root
            taskNames = tasks
            externalSystemIdString = GradleConstants.SYSTEM_ID.id
        }
        ExternalSystemUtil.runTask(
            settings,
            DefaultRunExecutor.EXECUTOR_ID,
            project,
            GradleConstants.SYSTEM_ID,
            null,
            ProgressExecutionMode.IN_BACKGROUND_ASYNC,
        )
    }

    private fun build(
        path: String,
        projectPath: String,
        root: String,
        roots: List<String>,
        tasks: Map<String, Set<String>>,
        executionRoot: String,
        executionId: String,
    ): BuildModule {
        val source = roots.filterNot { it.contains("/build/") || it.contains("/.gradle/") }.minByOrNull { it.length }
        val availableTasks = tasks[projectPath] ?: source?.let(tasks::get).orEmpty()
        val android = "testDebugUnitTest" in availableTasks ||
            roots.any { File(it, "src/main/AndroidManifest.xml").isFile }
        return BuildModule(
            id = path,
            root = root,
            contentRoots = roots,
            testTask = if (android) "testDebugUnitTest" else "test",
            compileTask = if (android) "compileDebugUnitTestKotlin" else "compileTestKotlin",
            hasTests = roots.any(::holdsTests),
            extraTasks = availableTasks,
            executionRoot = executionRoot,
            executionId = executionId,
        )
    }

    private fun holdsTests(root: String): Boolean = TEST_SOURCE_DIRS.any { directory ->
        File(root, directory).let { it.isDirectory && it.walkTopDown().any(::isSource) }
    }

    private fun buildRootOf(moduleDir: File, project: Project): String {
        var current: File? = moduleDir
        while (current != null) {
            if (SETTINGS_FILES.any { File(current, it).isFile }) return current.invariantSeparatorsPath
            current = current.parentFile
        }
        return project.basePath ?: moduleDir.invariantSeparatorsPath
    }

    private fun tasksByDirectory(project: Project): Map<String, Set<String>> {
        val result = HashMap<String, MutableSet<String>>()

        for (settings in GradleSettings.getInstance(project).linkedProjectsSettings) {
            val projectNode = ExternalSystemApiUtil.findProjectNode(
                project,
                GradleConstants.SYSTEM_ID,
                settings.externalProjectPath,
            ) ?: continue

            for (moduleNode in ExternalSystemApiUtil.findAll(projectNode, ProjectKeys.MODULE)) {
                val data: ModuleData = moduleNode.data
                val names = result.getOrPut(data.linkedExternalProjectPath) { mutableSetOf() }
                for (taskNode in ExternalSystemApiUtil.findAll(moduleNode, ProjectKeys.TASK)) {
                    val task: TaskData = taskNode.data
                    names += task.name.substringAfterLast(':')
                }
            }
        }
        return result
    }

    private companion object {
        const val SOURCE_SET_TYPE = "sourceSet"
        val SETTINGS_FILES = listOf("settings.gradle.kts", "settings.gradle")
        val TEST_SOURCE_DIRS = listOf("src/test", "src/testDebug", "src/commonTest", "src/jvmTest")

        fun isSource(file: File) = file.isFile && (file.extension == "kt" || file.extension == "java")
    }
}

internal fun gradleProjectPath(externalId: String, buildName: String?, sourceSet: Boolean): String {
    val parts = externalId.removePrefix(":").split(':').toMutableList()
    if (parts.firstOrNull() == buildName) parts.removeFirst()
    if (sourceSet && parts.lastOrNull() in SOURCE_SET_NAMES) parts.removeLast()
    return parts.joinToString(":", prefix = if (parts.isEmpty()) "" else ":")
}

internal fun gradleExecutionCoordinates(
    ownerRoot: String,
    ownerId: String,
    directoryToRunTask: String?,
    identityPath: String?,
): Pair<String, String> {
    if (directoryToRunTask.isNullOrBlank() || identityPath.isNullOrBlank()) return ownerRoot to ownerId
    return File(directoryToRunTask).invariantSeparatorsPath to identityPath.removeSuffix(":")
}

private val SOURCE_SET_NAMES = setOf("main", "unitTest", "androidTest", "test")
