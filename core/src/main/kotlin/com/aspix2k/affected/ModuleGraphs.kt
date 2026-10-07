package com.aspix2k.affected

import com.aspix2k.affected.build.BuildSystems
import com.aspix2k.affected.build.SuspendingBuildSystem
import com.intellij.openapi.project.Project

suspend fun ModuleGraph.Companion.create(project: Project): ModuleGraph {
    val nodes = BuildSystems.of(project).flatMap { system ->
        val modules = if (system is SuspendingBuildSystem) {
            system.modulesSuspending(project)
        } else {
            system.modules(project)
        }
        modules.map { ModuleGraph.Node(it, system) }
    }
    return ModuleGraph(nodes)
}
