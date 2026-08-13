package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.BuildSystem
import com.aspix2k.affected.build.BuildSystems
import com.aspix2k.affected.build.SuspendingBuildSystem
import com.aspix2k.affected.build.TransitiveTestConsumersBuildSystem
import com.aspix2k.affected.build.WorkspaceChangesBuildSystem
import com.intellij.openapi.project.Project
import java.io.File

class ModuleGraph internal constructor(private val nodes: List<Node>) {

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
            executionRoot = module.executionRoot,
            executionId = module.executionId,
            additionalTestTasks = module.additionalTestTasks,
        )
    }

    private val byContentRoot: Map<String, List<Node>> by lazy {
        val index = HashMap<String, MutableList<Node>>()
        for (node in nodes) {
            for (root in node.module.contentRoots) {
                index.getOrPut(root) { mutableListOf() } += node
            }
        }
        index
    }

    fun nodesFor(file: File): List<Node> {
        directBuildOwners(file)?.let { return it }

        var directory = file.parentFile
        while (directory != null) {
            byContentRoot[directory.invariantSeparatorsPath]?.let(::contentOwners)?.let { return it }
            directory = directory.parentFile
        }

        val path = file.toPath().toAbsolutePath().normalize()
        val owners = nodes.filter { node ->
            runCatching { path.startsWith(File(node.module.root).toPath().toAbsolutePath().normalize()) }
                .getOrDefault(false)
        }
        val deepest = owners.maxOfOrNull { File(it.module.root).toPath().nameCount } ?: return emptyList()
        return owners.filter { File(it.module.root).toPath().nameCount == deepest }.distinct()
    }

    fun nodeFor(file: File): Node? = nodesFor(file).firstOrNull()

    internal fun ownersForChanges(
        changes: BuildChanges,
        directOwners: Map<File, List<Node>> = changes.files.associate { path ->
            File(path).let { it to nodesFor(it) }
        },
    ): Map<File, List<Node>> {
        val workspaceNodes = HashMap<Pair<BuildSystem, String>, List<Node>>()
        return directOwners.mapValues { (_, owners) ->
            owners.groupBy { it.system to it.buildRoot }.flatMap { (key, group) ->
                val (system, root) = key
                val workspaceSystem = system as? WorkspaceChangesBuildSystem
                val requiresWorkspace = workspaceSystem != null &&
                    group.any { workspaceSystem.requiresWorkspace(it.module, changes) }
                if (requiresWorkspace) {
                    workspaceNodes.getOrPut(key) {
                        nodes.filter { it.system === system && it.buildRoot == root }
                    }
                } else {
                    group
                }
            }.distinct()
        }
    }

    fun directDependents(targets: Set<Node>): List<Node> {
        val targetKeys = targets.map { it.module.key }.toSet()

        return nodes.filter { node ->
            node !in targets && node.module.dependencies.any { it in targetKeys }
        }
    }

    fun transitiveTestConsumers(targets: Set<Node>): List<Node> {
        val eligibleTargets = targets.filterTo(LinkedHashSet()) {
            it.system is TransitiveTestConsumersBuildSystem
        }
        val reached = LinkedHashSet(eligibleTargets)
        var frontier = eligibleTargets
        while (frontier.isNotEmpty()) {
            val keys = frontier.mapTo(HashSet()) { it.module.key }
            frontier = nodes.filterTo(LinkedHashSet()) { node ->
                node.system is TransitiveTestConsumersBuildSystem &&
                    node !in reached &&
                    node.module.dependencies.any(keys::contains)
            }
            reached += frontier
        }
        val reachable = reached.filter { it !in eligibleTargets && it.module.testTask != it.module.compileTask }
        val productionRoots = eligibleTargets
            .filter { it.module.testTask == it.module.compileTask }
            .mapTo(HashSet()) { it.system.id to it.module.root }
        val conservative = nodes.filter { node ->
            node.system is TransitiveTestConsumersBuildSystem &&
                node.module.testTask != node.module.compileTask &&
                node.system.id to node.module.root in productionRoots
        }
        return (reachable + conservative).distinct()
    }

    fun all(): List<Node> = nodes

    private fun directBuildOwners(file: File): List<Node>? {
        val parent = file.parentFile?.toPath()?.toAbsolutePath()?.normalize() ?: return null
        val owners = nodes.filter { node ->
            runCatching { File(node.module.root).toPath().toAbsolutePath().normalize() == parent }
                .getOrDefault(false)
        }
        return owners.distinct().takeIf { it.isNotEmpty() }
    }

    private fun contentOwners(candidates: List<Node>): List<Node> {
        val (singleOwner, multiOwner) = candidates.distinct().partition { it.system.id in SINGLE_OWNER_SYSTEMS }
        return multiOwner + singleOwner
            .groupBy { it.system.id to it.module.root }
            .values
            .map { owners -> owners.maxBy { it.module.id.length } }
    }

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

private val SINGLE_OWNER_SYSTEMS = setOf("GRADLE", "MAVEN")
