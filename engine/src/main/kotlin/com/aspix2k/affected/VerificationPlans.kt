package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.BuildSystemTraits
import com.aspix2k.affected.build.gradle.isAndroidInstrumentationSource
import com.aspix2k.affected.build.gradle.selectAndroidTestTask
import java.io.File

data class ChangeSet(
    val files: List<File>,
    val apiTouched: Set<File>,
    val exactSelectionEligible: Set<File>,
    val comparedToBase: Boolean,
    val baseUnresolved: Boolean = false,
    val uncovered: List<File> = emptyList(),
    val resolvedBranch: String? = null,
    val gitUsable: Boolean = true,
    val mergeBase: String? = null,
    val outsideSources: Set<File> = emptySet(),
    val declaredOwners: Map<File, List<DeclaredOwner>>? = emptyMap(),
)

internal data class VerificationPlans(
    val testsOnly: Plan,
    val withConsumers: Plan,
    val unresolved: List<File> = emptyList(),
)

internal fun verificationPlans(
    graph: ModuleGraph,
    changes: ChangeSet,
    owners: Map<File, List<ModuleGraph.Node>>,
    testDependents: Boolean,
): VerificationPlans {
    if (changes.files.isEmpty()) {
        val empty = Plan(emptyList(), 0, 0)
        return VerificationPlans(empty, empty)
    }
    val effectiveOwners = graph.ownersForChanges(changes.toBuildChanges(), owners)
    val changed = effectiveOwners.values.flatten().distinct()
    val testConsumers = graph.transitiveTestConsumers(changed.toSet()) + if (testDependents) {
        val production = effectiveOwners.flatMapTo(HashSet()) { (file, nodes) ->
            nodes.filterNot { it.system.isTestSource(it.pathInBuildRoot(file)) }
        }
        graph.transitiveTestConsumers(production, everySystem = true)
    } else {
        emptyList()
    }
    val apiNodes = effectiveOwners.flatMapTo(HashSet()) { (file, nodes) ->
        nodes.filter { node ->
            affectsConsumers(
                system = node.system,
                path = node.pathInBuildRoot(file),
                signatureTouched = file in changes.apiTouched,
            )
        }
    }
    val changedNodes = changed.toSet()
    val tested = (changed + testConsumers).distinct().map { node ->
        androidTestInfo(node, if (node in changedNodes) pathsOwnedBy(node, effectiveOwners) else emptyList())
    }
    val testsOnly = TaskPlanner.plan(tested, emptyList())
    val consumers = if (apiNodes.isEmpty()) emptyList() else graph.directDependents(apiNodes)
    val verifiedByConsumers = changed.filterTo(HashSet()) { node ->
        !node.isVerifiable() && graph.transitiveTestConsumers(setOf(node), testDependents).any { it.isVerifiable() }
    }
    return VerificationPlans(
        testsOnly = testsOnly,
        withConsumers = if (consumers.isEmpty()) {
            testsOnly
        } else {
            TaskPlanner.plan(tested, consumers.map { it.info() })
        },
        unresolved = changes.files.filter { file ->
            val extension = file.extension.lowercase()
            extension in graph.sourceExtensions &&
                lacksOwnVerification(extension, effectiveOwners[file].orEmpty()) { it in verifiedByConsumers }
        },
    )
}

private fun ModuleGraph.Node.isVerifiable(): Boolean = module.hasTests || module.compileTask != null

private fun lacksOwnVerification(
    extension: String,
    owners: List<ModuleGraph.Node>,
    verifiedElsewhere: (ModuleGraph.Node) -> Boolean,
): Boolean {
    val verified = { node: ModuleGraph.Node -> node.isVerifiable() || verifiedElsewhere(node) }
    val claiming = owners.filter { extension in it.system.sourceExtensions }.groupBy { it.system.id }
    return if (claiming.isEmpty()) owners.none(verified) else claiming.values.any { it.none(verified) }
}

internal fun ChangeSet.toBuildChanges(): BuildChanges = BuildChanges(
    files = files.map { it.absoluteFile.normalize().invariantSeparatorsPath },
    exactSelectionEligible = exactSelectionEligible
        .mapTo(HashSet()) { it.absoluteFile.normalize().invariantSeparatorsPath },
    comparedToBase = comparedToBase,
    baseCommit = mergeBase,
    baseBranch = resolvedBranch,
)

internal fun affectsConsumers(system: BuildSystemTraits, path: String, signatureTouched: Boolean): Boolean =
    signatureTouched || !system.consumersNeedSignatureChange && !system.isTestSource(path)

private fun ModuleGraph.Node.pathInBuildRoot(file: File): String =
    file.invariantSeparatorsPath.removePrefix("${buildRoot.trimEnd('/')}/")

private fun pathsOwnedBy(
    node: ModuleGraph.Node,
    owners: Map<File, List<ModuleGraph.Node>>,
): List<String> = owners.mapNotNull { (file, nodes) ->
    file.invariantSeparatorsPath.takeIf { node in nodes }
}

private fun androidTestInfo(node: ModuleGraph.Node, changedPaths: List<String>): ModuleInfo {
    val info = node.info()
    if (info.systemId != "GRADLE" || changedPaths.isEmpty()) return info
    return info.copy(
        testTask = selectAndroidTestTask(
            info.testTask,
            node.module.extraTasks,
            changedPaths.all(::isAndroidInstrumentationSource),
        ),
    )
}
