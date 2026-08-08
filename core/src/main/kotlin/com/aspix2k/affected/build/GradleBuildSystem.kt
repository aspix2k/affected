package com.aspix2k.affected.build

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File

class GradleBuildSystem : BuildSystem {

    override val id: String = GradleConstants.SYSTEM_ID.id

    override val sourceExtensions: Set<String> = setOf("kt", "kts", "java", "xml", "json", "pro")

    override fun isPresent(project: Project): Boolean =
        GradleSettings.getInstance(project).linkedProjectsSettings.isNotEmpty()

    override fun modules(project: Project): List<BuildModule> {
        val tasks = tasksByDirectory(project)
        val result = LinkedHashMap<String, BuildModule>()
        val ideModules = HashMap<String, Module>()

        ModuleManager.getInstance(project).modules
            .mapNotNull { module -> describe(module)?.let { module to it } }
            .forEach { (module, described) ->
                val existing = result[described.key]
                result[described.key] = existing
                    ?.copy(contentRoots = (existing.contentRoots + described.roots).distinct())
                    ?: build(
                        ":${described.path}",
                        buildRootOf(File(described.projectPath), project),
                        described.roots,
                        tasks,
                    )
                ideModules[described.key] = module
            }

        return withDependencies(result, ideModules)
    }

    private data class Described(
        val key: String,
        val path: String,
        val projectPath: String,
        val roots: List<String>,
    )

    private fun describe(module: Module): Described? {
        if (!ExternalSystemApiUtil.isExternalSystemAwareModule(GradleConstants.SYSTEM_ID, module)) return null

        val projectPath = ExternalSystemApiUtil.getExternalProjectPath(module) ?: return null
        val externalId = ExternalSystemApiUtil.getExternalProjectId(module) ?: return null

        val path = externalId.substringAfter(':', "")
            .removeSuffix(":main")
            .removeSuffix(":unitTest")
            .removeSuffix(":androidTest")
            .removeSuffix(":test")
        if (path.isEmpty()) return null

        val roots = ModuleRootManager.getInstance(module).contentRoots.map { it.path }
        if (roots.isEmpty()) return null

        return Described("$projectPath|$path", path, projectPath, roots)
    }

    private fun withDependencies(
        modules: Map<String, BuildModule>,
        ideModules: Map<String, Module>,
    ): List<BuildModule> {
        val keyByIdeModule = ideModules.entries.associate { (key, module) -> module to modules.getValue(key).key }
        val keyByContentRoot = modules.values.flatMap { module -> module.contentRoots.map { it to module.key } }.toMap()

        return modules.map { (key, module) ->
            val ideModule = ideModules[key] ?: return@map module
            val direct = ModuleRootManager.getInstance(ideModule).dependencies
            val dependencies = direct.mapNotNullTo(HashSet()) { dependency ->
                keyByIdeModule[dependency]
                    ?: ModuleRootManager.getInstance(dependency).contentRoots
                        .firstNotNullOfOrNull { keyByContentRoot[it.path] }
            }
            module.copy(dependencies = dependencies - module.key)
        }
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
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
        root: String,
        roots: List<String>,
        tasks: Map<String, Set<String>>,
    ): BuildModule {
        val android = roots.any { File(it, "src/main/AndroidManifest.xml").isFile }
        val source = roots.filterNot { it.contains("/build/") || it.contains("/.gradle/") }.minByOrNull { it.length }
        return BuildModule(
            id = path,
            root = root,
            contentRoots = roots,
            testTask = if (android) "testDebugUnitTest" else "test",
            compileTask = if (android) "compileDebugUnitTestKotlin" else "compileTestKotlin",
            hasTests = roots.any(::holdsTests),
            extraTasks = source?.let { tasks[it] }.orEmpty(),
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
        val SETTINGS_FILES = listOf("settings.gradle.kts", "settings.gradle")
        val TEST_SOURCE_DIRS = listOf("src/test", "src/testDebug", "src/commonTest", "src/jvmTest")

        fun isSource(file: File) = file.isFile && (file.extension == "kt" || file.extension == "java")
    }
}
