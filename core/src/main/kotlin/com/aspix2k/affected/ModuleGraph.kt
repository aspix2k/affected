package com.aspix2k.affected

import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.BuildSystem
import com.aspix2k.affected.build.BuildSystems
import com.aspix2k.affected.build.SuspendingBuildSystem
import com.intellij.openapi.project.Project
import java.io.File

class ModuleGraph private constructor(private val nodes: List<Node>) {

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
        val targetKeys = targets.map { it.module.key }.toSet()

        return nodes.filter { node ->
            node !in targets && node.module.dependencies.any { it in targetKeys }
        }
    }

    fun all(): List<Node> = nodes

    companion object {
        suspend fun create(project: Project): ModuleGraph {
            val nodes = BuildSystems.of(project).flatMap { system ->
                val modules = if (system is SuspendingBuildSystem) {
                    system.modulesSuspending(project)
                } else {
                    system.modules(project)
                }
                modules.map { Node(it, system) }
            }
            return ModuleGraph(nodes)
        }
    }
}
