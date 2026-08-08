package com.aspix2k.affected

import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.BuildSystem
import com.aspix2k.affected.build.BuildSystems
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import java.io.File

class ModuleGraph(private val project: Project) {

    data class Node(val module: BuildModule, val system: BuildSystem) {

        val id: String get() = module.id
        val buildRoot: String get() = module.root
        val hasTests: Boolean get() = module.hasTests

        val sourceRoot: String? get() = module.contentRoots.minByOrNull { it.length }

        val testRoot: String? get() = sourceRoot?.let(TestRootResolver::resolve)

        fun info(): ModuleInfo = ModuleInfo(
            id = module.id,
            systemId = system.id,
            buildRoot = module.root,
            testTask = module.testTask,
            compileTask = module.compileTask,
            hasTests = module.hasTests,
        )
    }

    private val nodes: List<Node> by lazy {
        BuildSystems.of(project).flatMap { system ->
            system.modules(project).map { Node(it, system) }
        }
    }

    private val byContentRoot: Map<String, Node> by lazy {
        val index = HashMap<String, Node>()
        for (node in nodes) {
            for (root in node.module.contentRoots) {
                val existing = index[root]
                if (existing == null || node.module.id.length > existing.module.id.length) {
                    index[root] = node
                }
            }
        }
        index
    }

    fun nodeFor(file: File): Node? {
        var directory = file.parentFile
        while (directory != null) {
            byContentRoot[directory.invariantSeparatorsPath]?.let { return it }
            directory = directory.parentFile
        }
        return null
    }

    fun directDependents(targets: Set<Node>): List<Node> {
        val targetModules = targets.mapNotNull { it.module.ideModule }.toSet()
        val targetRoots = targets.flatMap { it.module.contentRoots }.toSet()

        return nodes.filter { node ->
            if (node in targets) return@filter false
            val ideModule = node.module.ideModule ?: return@filter false
            ModuleRootManager.getInstance(ideModule).dependencies.any { dependency ->
                dependency in targetModules ||
                    ModuleRootManager.getInstance(dependency).contentRoots.any { it.path in targetRoots }
            }
        }
    }

    fun all(): List<Node> = nodes
}
