package com.aspix2k.affected

import java.io.File

data class AffectedMcpSettings(
    val baseBranch: String,
    val checkConsumers: Boolean,
    val runBeforeCommit: Boolean = false,
    val runBeforePush: Boolean = false,
    val animateWhileRunning: Boolean = true,
)

data class AffectedMcpView(
    val text: String,
    val data: Map<String, Any?>,
    val error: Boolean = false,
)

object AffectedMcpViews {

    fun modules(snapshot: AffectedStateSnapshot): AffectedMcpView {
        notReady(snapshot)?.let { return it }
        val ids = snapshot.modules.map(AffectedModule::id)
        return AffectedMcpView(
            text = if (ids.isEmpty()) "No affected modules." else "Affected modules: ${ids.size}",
            data = mapOf(
                "analysisStatus" to "ready",
                "revision" to snapshot.revision,
                "modules" to ids,
            ),
        )
    }

    fun plan(snapshot: AffectedStateSnapshot, checkConsumers: Boolean): AffectedMcpView {
        notReady(snapshot)?.let { return it }
        val prepared = snapshot.plans?.select(checkConsumers)
            ?: return unavailable("Prepared verification data is not available.")
        val tasks = prepared.plan.groups.flatMap(TaskGroup::tasks)
        return AffectedMcpView(
            text = when {
                prepared.plan.isEmpty -> "Nothing to verify."
                else -> "Modules to test: ${prepared.plan.tested}, consumers to compile: ${prepared.plan.compiled}"
            },
            data = mapOf(
                "analysisStatus" to "ready",
                "revision" to snapshot.revision,
                "tested" to prepared.plan.tested,
                "compiled" to prepared.plan.compiled,
                "tasks" to tasks,
                "groups" to prepared.plan.groups.map { group ->
                    mapOf("systemId" to group.systemId, "root" to group.root, "tasks" to group.tasks)
                },
            ),
        )
    }

    fun changedFiles(snapshot: AffectedStateSnapshot, basePath: String?): AffectedMcpView {
        notReady(snapshot)?.let { return it }
        val changes = snapshot.changes
            ?: return unavailable("Changed files are not available.")
        val root = basePath?.let(::File)
        val files = changes.files.map { relative(it, root) }
        val apiTouched = changes.apiTouched.map { relative(it, root) }
        return AffectedMcpView(
            text = if (files.isEmpty()) {
                "No source changes."
            } else {
                "Changed files: ${files.size}, of them API-changing: ${apiTouched.size}"
            },
            data = mapOf(
                "analysisStatus" to "ready",
                "revision" to snapshot.revision,
                "files" to files,
                "apiTouched" to apiTouched,
                "comparedToBase" to changes.comparedToBase,
            ),
        )
    }

    fun status(
        snapshot: AffectedStateSnapshot,
        settings: AffectedMcpSettings,
        ownedRunning: Int,
    ): AffectedMcpView {
        val analysis = snapshot.analysisStatus.name.lowercase()
        return AffectedMcpView(
            text = "Analysis: $analysis. Verification: ${snapshot.verificationStatus.name.lowercase()}.",
            data = mapOf(
                "analysisStatus" to analysis,
                "verificationStatus" to snapshot.verificationStatus.name.lowercase(),
                "revision" to snapshot.revision,
                "affectedModules" to snapshot.affectedModules,
                "ownedRunning" to ownedRunning,
                "baseBranch" to settings.baseBranch,
                "checkConsumers" to settings.checkConsumers,
                "runBeforeCommit" to settings.runBeforeCommit,
                "runBeforePush" to settings.runBeforePush,
                "animateWhileRunning" to settings.animateWhileRunning,
            ),
        )
    }

    fun availableTasks(snapshot: AffectedStateSnapshot): AffectedMcpView {
        notReady(snapshot)?.let { return it }
        val counts = snapshot.modules
            .flatMap(AffectedModule::tasks)
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        return AffectedMcpView(
            text = if (counts.isEmpty()) "No tasks on affected modules." else "Tasks available on affected modules.",
            data = mapOf(
                "analysisStatus" to "ready",
                "revision" to snapshot.revision,
                "tasks" to counts.map { (task, count) -> mapOf("name" to task, "modules" to count) },
            ),
        )
    }

    internal fun notReady(snapshot: AffectedStateSnapshot): AffectedMcpView? = when (snapshot.analysisStatus) {
        AnalysisStatus.ANALYZING -> AffectedMcpView(
            text = "Affected is still analyzing the current revision.",
            data = mapOf(
                "analysisStatus" to "analyzing",
                "revision" to snapshot.revision,
            ),
            error = true,
        )
        AnalysisStatus.UNAVAILABLE -> unavailable("Affected analysis is unavailable.")
        AnalysisStatus.READY -> null
    }

    private fun unavailable(text: String) = AffectedMcpView(
        text = text,
        data = mapOf("analysisStatus" to "unavailable"),
        error = true,
    )

    private fun relative(file: File, root: File?): String {
        if (root == null) return file.invariantSeparatorsPath
        return file.relativeTo(root).invariantSeparatorsPath
    }
}
