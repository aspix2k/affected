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

    fun nodeFor(file: File): Node? {
        val path = file.invariantSeparatorsPath
        var best: Node? = null
        var bestLength = -1
        for (node in nodes) {
            for (root in node.module.contentRoots) {
                if (path.startsWith("$root/") && root.length > bestLength) {
                    best = node
                    bestLength = root.length
                }
            }
        }
        return best
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
