package com.aspix2k.affected.mcp

import com.aspix2k.affected.AffectedMcpInputs
import com.aspix2k.affected.AffectedMcpSettings
import com.aspix2k.affected.AffectedMcpView
import com.aspix2k.affected.AffectedMcpViews
import com.aspix2k.affected.AffectedModule
import com.aspix2k.affected.AffectedRunSessions
import com.aspix2k.affected.AffectedSettings
import com.aspix2k.affected.AffectedState
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
        "Lists the modules affected by the current changes from the same analysis snapshot as the toolbar."
    )
    suspend fun affected_modules(): McpToolCallResult =
        AffectedMcpViews.modules(snapshot(coroutineContext.project)).toResult()

    @McpTool
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE)
    @McpDescription(
        "Returns the prepared verification tasks from the current analysis snapshot without recomputing it."
    )
    suspend fun affected_verification_plan(): McpToolCallResult {
        val project = coroutineContext.project
        if (project.basePath == null) return noBasePath()
        return AffectedMcpViews.plan(snapshot(project), settings().checkConsumers).toResult()
    }

    @McpTool
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE)
    @McpDescription(
        "Lists files from the current analysis snapshot, marking those that change public API."
    )
    suspend fun affected_changed_files(): McpToolCallResult {
        val project = coroutineContext.project
        val basePath = project.basePath ?: return noBasePath()
        return AffectedMcpViews.changedFiles(snapshot(project), basePath).toResult()
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
        val preview = AffectedMcpViews.plan(state.snapshot(), settings().checkConsumers)
        if (preview.error) return preview.toResult()
        val claim = state.tryClaimReadyRun() ?: return cannotClaim()
        val prepared = claim.prepared ?: run {
            claim.close()
            return unavailablePlan()
        }
        if (prepared.plan.isEmpty) {
            claim.close()
            return preview.toResult()
        }
        if (projectBusy(project)) {
            claim.close()
            return busy()
        }
        val outcome = Verification.runClaimedAndWait(project, prepared, claim)
        return preview.copy(
            text = "${if (outcome.passed) "Passed" else "Failed"}. ${preview.text}",
            data = preview.data + ("passed" to outcome.passed),
        ).toResult()
    }

    @McpTool
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription(
        "Runs a named task on every affected module that declares it, using the same exclusive lease as the toolbar."
    )
    suspend fun affected_run_task(
        @McpDescription("Gradle task name without module path, for example detekt")
        task: String,
    ): McpToolCallResult {
        val project = coroutineContext.project
        if (project.basePath == null) return noBasePath()
        val state = project.service<AffectedState>()
        val validation = AffectedMcpInputs.validateNamedTask(state.snapshot(), task)
        if (validation.error) return validation.toResult()
        saveDocuments()
        if (projectBusy(project)) return busy()
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
            val passed = runClaimedGroups(claim, groups, Dispatchers.IO, stopAfterFirstFailure) { group ->
                group.runInPlannedExecutionRoot(project) {
                    runWithRequiredAdapter(BuildSystems.byId(group.systemId)) {
                        it.runAndWait(project, group.root, group.tasks)
                    }
                }
            }
            validation.copy(
                text = "${if (passed) "Passed" else "Failed"}. ${validation.text}",
                data = validation.data + ("passed" to passed),
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
            settings = settings(),
            ownedRunning = AffectedRunSessions.getInstance(project).activeCount(),
        ).toResult()
    }

    @McpTool
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE)
    @McpDescription(
        "Lists tasks declared by the current affected modules so they can be passed to affected_run_task."
    )
    suspend fun affected_available_tasks(): McpToolCallResult =
        AffectedMcpViews.availableTasks(snapshot(coroutineContext.project)).toResult()

    @McpTool
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription(
        "Changes plugin settings: the base branch, consumer compilation, commit and push guards, and running animation."
    )
    suspend fun affected_configure(
        @McpDescription("Base branch, for example develop, main or master")
        baseBranch: String? = null,
        @McpDescription("Whether to compile modules consuming a changed public API")
        checkConsumers: Boolean? = null,
        @McpDescription("Whether to run verification before commit")
        runBeforeCommit: Boolean? = null,
        @McpDescription("Whether to run verification before push")
        runBeforePush: Boolean? = null,
        @McpDescription("Whether to animate the toolbar icon while verification is running")
        animateWhileRunning: Boolean? = null,
    ): McpToolCallResult {
        val view = AffectedMcpInputs.applySettings(
            current = settings(),
            baseBranch = baseBranch,
            checkConsumers = checkConsumers,
            runBeforeCommit = runBeforeCommit,
            runBeforePush = runBeforePush,
            animateWhileRunning = animateWhileRunning,
        )
        if (view.error) return view.toResult()
        val next = AffectedSettings.getInstance()
        next.baseBranch = view.data["baseBranch"] as String
        next.checkConsumers = view.data["checkConsumers"] as Boolean
        next.runBeforeCommit = view.data["runBeforeCommit"] as Boolean
        next.runBeforePush = view.data["runBeforePush"] as Boolean
        next.animateWhileRunning = view.data["animateWhileRunning"] as Boolean
        coroutineContext.project.service<AffectedState>().invalidate()
        return view.toResult()
    }

    private fun snapshot(project: Project) = project.service<AffectedState>().snapshot()

    private fun settings(): AffectedMcpSettings {
        val current = AffectedSettings.getInstance()
        return AffectedMcpSettings(
            baseBranch = current.baseBranch,
            checkConsumers = current.checkConsumers,
            runBeforeCommit = current.runBeforeCommit,
            runBeforePush = current.runBeforePush,
            animateWhileRunning = current.animateWhileRunning,
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
