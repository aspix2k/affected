package com.aspix2k.affected.mcp

import com.aspix2k.affected.AffectedMcpInputs
import com.aspix2k.affected.AffectedMcpSettings
import com.aspix2k.affected.AffectedMcpView
import com.aspix2k.affected.AffectedMcpViews
import com.aspix2k.affected.AffectedModule
import com.aspix2k.affected.AffectedRunSessions
import com.aspix2k.affected.AffectedSettings
import com.aspix2k.affected.AffectedState
import com.aspix2k.affected.AffectedStateSnapshot
import com.aspix2k.affected.ProjectBaseBranch
import com.aspix2k.affected.TaskPlanner
import com.aspix2k.affected.Verification
import com.aspix2k.affected.build.BuildSystems
import com.aspix2k.affected.projectBusy
import com.aspix2k.affected.runClaimedGroups
import com.aspix2k.affected.runWithRequiredAdapter
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.coroutineContext

class AffectedToolset : McpToolset {

    override fun isEnabled(): Boolean = true

    @McpTool
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE)
    @McpDescription(
        "Lists the modules affected by the current changes, re-reading the changes before answering."
    )
    suspend fun affected_modules(): McpToolCallResult =
        AffectedMcpViews.modules(freshSnapshot(coroutineContext.project)).toResult()

    @McpTool
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE)
    @McpDescription(
        "Returns the verification tasks for the current changes, re-reading the changes before answering."
    )
    suspend fun affected_verification_plan(): McpToolCallResult {
        val project = coroutineContext.project
        if (project.basePath == null) return noBasePath()
        return AffectedMcpViews.plan(freshSnapshot(project), settings(project).checkConsumers).toResult()
    }

    @McpTool
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE)
    @McpDescription(
        "Lists the changed files, re-reading them before answering, and marks those that change public API."
    )
    suspend fun affected_changed_files(): McpToolCallResult {
        val project = coroutineContext.project
        val basePath = project.basePath ?: return noBasePath()
        return AffectedMcpViews.changedFiles(freshSnapshot(project), basePath).toResult()
    }

    @McpTool
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription(
        "Runs the prepared verification through the same exclusive lease as the toolbar, commit and push guards."
    )
    suspend fun affected_run_verification(): McpToolCallResult {
        val project = coroutineContext.project
        if (project.basePath == null) return noBasePath()
        saveDocuments()
        if (projectBusy(project)) return busy()
        val state = project.service<AffectedState>()
        state.refreshNow()
        val preview = AffectedMcpViews.plan(state.snapshot(), settings(project).checkConsumers)
        if (preview.error) return preview.toResult()
        val claim = state.tryClaimReadyRun() ?: return cannotClaim()
        val prepared = claim.prepared ?: run {
            claim.close()
            return unavailablePlan()
        }
        if (!prepared.plan.isEmpty && projectBusy(project)) {
            claim.close()
            return busy()
        }
        val outcome = Verification.runClaimedAndWait(project, prepared, claim)
        return AffectedMcpViews.withUncovered(verificationView(claim.snapshot, outcome), prepared.uncovered).toResult()
    }

    @McpTool
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.TRUE)
    @McpDescription(
        "Runs any named task declared by an affected module, including tasks that publish or delete, " +
            "using the same exclusive lease as the toolbar."
    )
    suspend fun affected_run_task(
        @McpDescription("Gradle task name without module path, for example detekt")
        task: String,
    ): McpToolCallResult {
        val project = coroutineContext.project
        if (project.basePath == null) return noBasePath()
        val state = project.service<AffectedState>()
        saveDocuments()
        if (projectBusy(project)) return busy()
        state.refreshNow()
        val validation = AffectedMcpInputs.validateNamedTask(state.snapshot(), task)
        if (validation.error) return validation.toResult()
        val claim = state.tryClaimReadyRun() ?: return cannotClaim()
        val name = validation.data["task"] as String
        val modules = claim.snapshot.modules.filter { it.supports(name) }
        if (modules.isEmpty()) {
            claim.close()
            return AffectedMcpInputs.validateNamedTask(claim.snapshot, name).toResult()
        }
        if (projectBusy(project)) {
            claim.close()
            return busy()
        }
        if (!claim.markRunning()) {
            claim.close()
            return cannotClaim()
        }
        return try {
            val groups = TaskPlanner.groups(modules.map(AffectedModule::info), name)
            val stopAfterFirstFailure = AffectedSettings.getInstance().stopAfterFirstFailure
            val passed = runClaimedGroups(project, claim, groups, Dispatchers.IO, stopAfterFirstFailure) { group ->
                group.runInPlannedExecutionRoot(project) {
                    runWithRequiredAdapter(BuildSystems.byId(group.systemId)) {
                        it.runAndWait(project, group.root, group.tasks)
                    }
                }
            }
            validation.copy(
                text = "${if (passed) "Passed" else "Failed"}. ${validation.text}",
                data = validation.data + ("passed" to passed),
                error = !passed,
            ).toResult()
        } finally {
            claim.close()
        }
    }

    @McpTool
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.TRUE)
    @McpDescription(
        "Stops only Affected-owned verification and named-task sessions. Unrelated IDE Run processes are left running."
    )
    suspend fun affected_stop(): McpToolCallResult {
        val project = coroutineContext.project
        val stopped = withContext(Dispatchers.EDT) {
            AffectedRunSessions.getInstance(project).stopOwned()
        }
        val view = AffectedMcpView(
            text = if (stopped == 0) "Nothing owned by Affected is running." else "Stopped $stopped Affected run(s).",
            data = mapOf("stopped" to stopped),
        )
        return view.toResult()
    }

    @McpTool
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE)
    @McpDescription(
        "Reports the current analysis snapshot, settings and the number of Affected-owned running sessions."
    )
    suspend fun affected_status(): McpToolCallResult {
        val project = coroutineContext.project
        return AffectedMcpViews.status(
            snapshot = snapshot(project),
            settings = settings(project),
            ownedRunning = AffectedRunSessions.getInstance(project).activeCount(),
        ).toResult()
    }

    @McpTool
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE)
    @McpDescription(
        "Lists tasks declared by the current affected modules so they can be passed to affected_run_task."
    )
    suspend fun affected_available_tasks(): McpToolCallResult =
        AffectedMcpViews.availableTasks(freshSnapshot(coroutineContext.project)).toResult()

    @McpTool
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription(
        "Changes plugin settings: this project's base branch, consumer compilation, commit and push guards, " +
            "and running animation."
    )
    suspend fun affected_configure(
        @McpDescription("Base branch of this project, for example develop, main or master; empty or auto detects it")
        baseBranch: String? = null,
        @McpDescription("Whether to compile modules consuming a changed public API")
        checkConsumers: Boolean? = null,
        @McpDescription("Whether to run verification before commit")
        runBeforeCommit: Boolean? = null,
        @McpDescription("Whether to run verification before push")
        runBeforePush: Boolean? = null,
        @McpDescription("Whether to animate the toolbar icon while verification is running")
        animateWhileRunning: Boolean? = null,
        @McpDescription("Whether to also run the tests of modules that depend on a changed module")
        testDependents: Boolean? = null,
    ): McpToolCallResult {
        val project = coroutineContext.project
        val view = AffectedMcpInputs.applySettings(
            current = settings(project),
            baseBranch = baseBranch,
            checkConsumers = checkConsumers,
            runBeforeCommit = runBeforeCommit,
            runBeforePush = runBeforePush,
            animateWhileRunning = animateWhileRunning,
            testDependents = testDependents,
        )
        if (view.error) return view.toResult()
        val next = AffectedSettings.getInstance()
        if (baseBranch != null) project.service<ProjectBaseBranch>().configure(view.data["baseBranch"] as String)
        next.checkConsumers = view.data["checkConsumers"] as Boolean
        next.testDependents = view.data["testDependents"] as Boolean
        next.runBeforeCommit = view.data["runBeforeCommit"] as Boolean
        next.runBeforePush = view.data["runBeforePush"] as Boolean
        next.animateWhileRunning = view.data["animateWhileRunning"] as Boolean
        invalidateProjects(ProjectManager.getInstance().openProjects.asList()) {
            it.service<AffectedState>().invalidate()
        }
        return view.toResult()
    }

    private fun snapshot(project: Project) = project.service<AffectedState>().snapshot()

    private suspend fun freshSnapshot(project: Project): AffectedStateSnapshot {
        val state = project.service<AffectedState>()
        if (!state.isRunning && !projectBusy(project)) state.refreshNow()
        return state.snapshot()
    }

    private fun settings(project: Project): AffectedMcpSettings {
        val current = AffectedSettings.getInstance()
        return AffectedMcpSettings(
            baseBranch = project.service<ProjectBaseBranch>().configured ?: ProjectBaseBranch.AUTO_BRANCH,
            resolvedBaseBranch = snapshot(project).changes?.resolvedBranch,
            checkConsumers = current.checkConsumers,
            runBeforeCommit = current.runBeforeCommit,
            runBeforePush = current.runBeforePush,
            animateWhileRunning = current.animateWhileRunning,
            testDependents = current.testDependents,
        )
    }

    private fun saveDocuments() {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            FileDocumentManager.getInstance().saveAllDocuments()
        } else {
            application.invokeAndWait { FileDocumentManager.getInstance().saveAllDocuments() }
        }
    }
}

internal fun AffectedMcpView.toResult(): McpToolCallResult {
    val structured = data.toJsonObject()
    return if (error) McpToolCallResult.error(text, structured) else McpToolCallResult.text(text, structured)
}

internal fun verificationView(snapshot: AffectedStateSnapshot, outcome: Verification.Outcome): AffectedMcpView {
    val plan = AffectedMcpViews.plan(snapshot, outcome.plan)
    val data = plan.data + ("passed" to outcome.passed)
    return when (outcome.blocker) {
        Verification.Blocker.UNRESOLVED_CHANGES -> plan.copy(
            text = if (outcome.plan.isEmpty) {
                "Failed. Changes exist but no verification could be planned."
            } else {
                "Failed. Planned tests ran, but some changed files belong to no known build module."
            },
            data = data + ("reason" to "unresolved-changes"),
            error = true,
        )
        Verification.Blocker.NO_COMPARISON_BASE -> plan.copy(
            text = "Failed. No comparison base: the base branch was not found, so committed changes are unknown.",
            data = data + ("reason" to "no-comparison-base"),
            error = true,
        )
        Verification.Blocker.NOT_STARTED -> plan.copy(
            text = "Failed. Verification did not start because Affected is busy.",
            data = data + ("reason" to "not-started"),
            error = true,
        )
        null -> plan.copy(
            text = "${if (outcome.passed) "Passed" else "Failed"}. ${plan.text}",
            data = data,
            error = !outcome.passed,
        )
    }
}

internal fun invalidateProjects(projects: List<Project>, invalidate: (Project) -> Unit) {
    projects.filterNot(Project::isDisposed).forEach(invalidate)
}

private fun noBasePath() = AffectedMcpView(
    text = "Project has no base path.",
    data = mapOf("reason" to "no-base-path"),
    error = true,
).toResult()

private fun busy() = AffectedMcpView(
    text = "The IDE is busy and cannot start Affected work.",
    data = mapOf("reason" to "busy"),
    error = true,
).toResult()

private fun cannotClaim() = AffectedMcpView(
    text = "Affected cannot start another run until the current exclusive session finishes.",
    data = mapOf("reason" to "busy"),
    error = true,
).toResult()

private fun unavailablePlan() = AffectedMcpView(
    text = "Prepared verification data is not available.",
    data = mapOf("reason" to "unavailable"),
    error = true,
).toResult()

private fun Map<String, Any?>.toJsonObject(): JsonObject = JsonObject(mapValues { (_, value) -> value.toJsonElement() })

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(this.entries.associate { (key, value) -> key.toString() to value.toJsonElement() })
    is Iterable<*> -> JsonArray(this.map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}
