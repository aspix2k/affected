package com.aspix2k.affected

import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File

class ModuleGraph(private val project: Project) {

    data class Node(
        val module: Module,
        val gradlePath: String,
        val buildRoot: String,
        val contentRoots: List<VirtualFile>,
    ) {
        val isAndroid: Boolean
            get() = contentRoots.any { File(it.path, "src/main/AndroidManifest.xml").isFile }

        val sourceRoot: String?
            get() = contentRoots
                .map { it.path }
                .filterNot { it.contains("/build/") || it.contains("/.gradle/") }
                .minByOrNull { it.length }

        val testRoot: String?
            get() = sourceRoot?.let(TestRootResolver::resolve)

        val hasTests: Boolean
            get() = contentRoots.any { root ->
                TEST_SOURCE_DIRS.any { dir ->
                    File(root.path, dir).let { it.isDirectory && it.walkTopDown().any(::isSource) }
                }
            }

        fun info(): ModuleInfo = ModuleInfo(gradlePath, buildRoot, isAndroid, hasTests)

        private companion object {
            val TEST_SOURCE_DIRS = listOf("src/test", "src/testDebug", "src/commonTest", "src/jvmTest")

            fun isSource(file: File) =
                file.isFile && (file.extension == "kt" || file.extension == "java")
        }
    }

    private val nodes: List<Node> by lazy { collect() }

    private fun collect(): List<Node> {
        val result = LinkedHashMap<String, Node>()

        for (module in ModuleManager.getInstance(project).modules) {
            if (!ExternalSystemApiUtil.isExternalSystemAwareModule(GradleConstants.SYSTEM_ID, module)) continue

            val projectPath = ExternalSystemApiUtil.getExternalProjectPath(module) ?: continue
            val id = ExternalSystemApiUtil.getExternalProjectId(module) ?: continue

            val gradlePath = id.substringAfter(':', "")
                .removeSuffix(":main")
                .removeSuffix(":unitTest")
                .removeSuffix(":androidTest")
                .removeSuffix(":test")
            if (gradlePath.isEmpty()) continue

            val roots = ModuleRootManager.getInstance(module).contentRoots.toList()
            if (roots.isEmpty()) continue

            val key = "$projectPath|$gradlePath"
            val existing = result[key]
            if (existing == null) {
                result[key] = Node(module, ":$gradlePath", buildRootOf(File(projectPath)), roots)
            } else {
                result[key] = existing.copy(contentRoots = (existing.contentRoots + roots).distinct())
            }
        }
        return result.values.toList()
    }

    private fun buildRootOf(moduleDir: File): String {
        var current: File? = moduleDir
        while (current != null) {
            if (SETTINGS_FILES.any { File(current, it).isFile }) return current.path
            current = current.parentFile
        }
        return project.basePath ?: moduleDir.path
    }

    fun nodeFor(file: File): Node? {
        val path = file.invariantSeparatorsPath
        var best: Node? = null
        var bestLength = -1
        for (node in nodes) {
            for (root in node.contentRoots) {
                val rootPath = root.path
                if (path.startsWith("$rootPath/") && rootPath.length > bestLength) {
                    best = node
                    bestLength = rootPath.length
                }
            }
        }
        return best
    }

    fun directDependents(targets: Set<Node>): List<Node> {
        val targetModules = targets.map { it.module }.toSet()
        val targetRoots = targets.flatMap { it.contentRoots }.map { it.path }.toSet()

        return nodes.filter { node ->
            if (node in targets) return@filter false
            val dependencies = ModuleRootManager.getInstance(node.module).dependencies
            dependencies.any { dependency ->
                dependency in targetModules ||
                    ModuleRootManager.getInstance(dependency).contentRoots.any { it.path in targetRoots }
            }
        }
    }

    fun all(): List<Node> = nodes

    private companion object {
        val SETTINGS_FILES = listOf("settings.gradle.kts", "settings.gradle")
    }
}
