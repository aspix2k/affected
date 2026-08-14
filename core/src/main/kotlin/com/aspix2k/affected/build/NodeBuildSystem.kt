package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class NodeBuildSystem : ChangeAwareSuspendingBuildSystem, AllFileChangesBuildSystem {

    private data class Snapshot(val root: String, val stamp: String, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "NODE"

    override val sourceExtensions: Set<String> =
        setOf("ts", "tsx", "js", "jsx", "mjs", "cjs", "json", "vue", "svelte", "yaml", "yml", "lock")

    override fun isPresent(project: Project): Boolean = rootOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = rootOf(project) ?: return emptyList()
        val packageManifests = NodeWorkspaces.manifestFiles(root)
        val stamp = packageManifests?.let { manifests ->
            combineFingerprints(
                ManifestSearch.fingerprint(root, manifestInputs(root, manifests)),
                combineFingerprints(
                    manifests.map { manifest ->
                        ManifestSearch.layoutFingerprint(manifest.parentFile, maxDepth = 0) {
                            it.name in NODE_TEST_MARKERS
                        }
                    },
                ),
            )
        }

        val rootPath = root.invariantSeparatorsPath
        if (stamp != null) cache.get()?.takeIf { it.root == rootPath && it.stamp == stamp }?.let { return it.modules }

        val discovered = runCatching { NodeWorkspaces.parse(root) }.getOrNull()
        val discovery = failClosedModules(root, NodeWorkspaces.TEST, null, discovered)
        if (stamp != null && discovery.complete) {
            cache.retainBuildSnapshot(Snapshot(rootPath, stamp, discovery.modules), discovery.modules.size)
        }
        return discovery.modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, nodeCommands(root, tasks), "Affected Node")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, nodeCommands(root, tasks), "Affected Node")

    override suspend fun runAndWaitSuspending(
        project: Project,
        root: String,
        tasks: List<String>,
        changes: BuildChanges,
    ): Boolean {
        val commands = withContext(Dispatchers.IO) { nodeCommands(root, tasks, changes) }
        return CommandRunner.runBatchAndWait(project, root, commands, "Affected Node")
    }

    private fun manifestInputs(root: File, manifests: List<File>): List<File> =
        manifests + manifests.mapNotNull { manifest ->
            File(manifest.parentFile, "tsconfig.json").takeIf(File::isFile)
        } + listOf(
            "pnpm-workspace.yaml",
            "pnpm-lock.yaml",
            "yarn.lock",
            "package-lock.json",
            "bun.lock",
            "bun.lockb",
        )
            .map { File(root, it) }
            .filter(File::isFile)

    private fun rootOf(project: Project): File? =
        project.basePath?.let(::File)?.let { nestedBuildRoot(it) { File(it, "package.json").isRegularFileNoFollow() } }
}

private val NODE_TEST_MARKERS = setOf("__tests__", "test", "tests", "spec")

internal fun nodeCommands(root: String, tasks: List<String>): List<CliCommand> {
    val manager = nodeManager(File(root))
    return tasks.groupBy({ it.substringAfterLast(':') }, { it.substringBeforeLast(':') })
        .flatMap { (task, packages) ->
            nodeCommands(manager, task, packages.distinct())
        }
}

internal fun nodeCommands(root: String, tasks: List<String>, changes: BuildChanges): List<CliCommand> {
    if (!changes.comparedToBase) return nodeCommands(root, tasks)
    val selections = nodeRelatedTestSelections(File(root), tasks, changes)
    if (selections.isEmpty()) return nodeCommands(root, tasks)

    val manager = nodeManager(File(root))
    return tasks.groupBy({ it.substringAfterLast(':') }, { it.substringBeforeLast(':') })
        .flatMap { (task, rawPackages) ->
            val packages = rawPackages.distinct()
            if (task != NodeWorkspaces.TEST) {
                nodeCommands(manager, task, packages)
            } else {
                val full = packages.filterNot(selections::containsKey)
                nodeCommands(manager, task, full) + packages.mapNotNull { packageName ->
                    selections[packageName]?.let { nodeRelatedTestCommand(manager, packageName, it) }
                }
            }
        }
}

private fun nodeCommands(manager: String, task: String, packages: List<String>): List<CliCommand> {
    val root = packages.filter { it == "." }
    val workspaces = packages.filterNot { it == "." }
    val commands = ArrayList<CliCommand>()
    root.forEach { name -> commands += nodeCommand(manager, task, listOf(name)) }
    if (manager == "yarn") {
        workspaces.forEach { name -> commands += nodeCommand(manager, task, listOf(name)) }
    } else if (workspaces.isNotEmpty()) {
        commands += nodeCommand(manager, task, workspaces)
    }
    return commands
}

private fun nodeCommand(manager: String, task: String, packages: List<String>): CliCommand =
    if (task == NodeWorkspaces.TEST) {
        CliCommand("$manager test ${packages.joinToString()}", nodeTestCommand(manager, packages))
    } else {
        CliCommand("typecheck ${packages.joinToString()}", nodeTypeCheckCommand(manager, packages))
    }

private fun nodeRelatedTestCommand(
    manager: String,
    packageName: String,
    selection: NodeRelatedTestSelection,
): CliCommand {
    val runner = when (selection.runner) {
        NodeTestRunner.JEST -> listOf("jest", "--findRelatedTests", "--passWithNoTests")
        NodeTestRunner.VITEST -> listOf("vitest", "related", "--run", "--passWithNoTests")
    }
    val executable = when (manager) {
        "pnpm" -> if (packageName == ".") {
            listOf("pnpm", "exec")
        } else {
            listOf("pnpm", "--filter", packageName, "exec")
        }
        "yarn" -> if (packageName == ".") {
            listOf("yarn", "exec")
        } else {
            listOf("yarn", "workspace", packageName, "exec")
        }
        else -> if (packageName == ".") {
            listOf("npm", "exec", "--")
        } else {
            listOf("npm", "exec", "--workspace", packageName, "--")
        }
    }
    return CliCommand(
        "$manager ${selection.runner.displayName} related $packageName",
        executable + runner + selection.files,
    )
}

private fun nodeManager(root: File): String = bunManager(root) ?: unambiguousNodeManager(root) ?: when {
    File(root, "pnpm-lock.yaml").isFile || File(root, "pnpm-workspace.yaml").isFile -> "pnpm"
    File(root, "yarn.lock").isFile -> "yarn"
    else -> "npm"
}

private fun nodeTestCommand(manager: String, packages: List<String>): List<String> = when (manager) {
    "pnpm" -> if (packages == listOf(".")) {
        listOf("pnpm", "test")
    } else {
        listOf("pnpm") + packages.flatMap { listOf("--filter", it) } + "test"
    }
    "yarn" -> if (packages == listOf(".")) {
        listOf("yarn", "test")
    } else {
        listOf("yarn", "workspace", packages.single(), "test")
    }
    "bun" -> if (packages == listOf(".")) {
        listOf("bun", "test")
    } else {
        listOf("bun") + packages.flatMap { listOf("--filter", it) } + "test"
    }
    else -> if (packages == listOf(".")) {
        listOf("npm", "test")
    } else {
        listOf("npm", "test") + packages.flatMap { listOf("--workspace", it) }
    }
}

private fun nodeTypeCheckCommand(manager: String, packages: List<String>): List<String> = when (manager) {
    "pnpm" -> if (packages == listOf(".")) {
        listOf("pnpm", "exec", "tsc", "--noEmit")
    } else {
        listOf("pnpm") + packages.flatMap { listOf("--filter", it) } + listOf("exec", "tsc", "--noEmit")
    }
    "yarn" -> if (packages == listOf(".")) {
        listOf("yarn", "exec", "tsc", "--noEmit")
    } else {
        listOf("yarn", "workspace", packages.single(), "exec", "tsc", "--noEmit")
    }
    "bun" -> if (packages == listOf(".")) {
        listOf("bun", "x", "tsc", "--noEmit")
    } else {
        listOf("bun") + packages.flatMap { listOf("--filter", it) } + listOf("x", "tsc", "--noEmit")
    }
    else -> if (packages == listOf(".")) {
        listOf("npm", "exec", "--", "tsc", "--noEmit")
    } else {
        listOf("npm", "exec") + packages.flatMap { listOf("--workspace", it) } + listOf("--", "tsc", "--noEmit")
    }
}
