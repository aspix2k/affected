package com.aspix2k.affected.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.execution.ui.RunContentManager
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.intellij.openapi.components.service
import com.aspix2k.affected.AffectedSettings
import com.aspix2k.affected.build.BuildSystems
import com.aspix2k.affected.AffectedState
import com.aspix2k.affected.ChangeAnalyzer
import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskGroup
import com.aspix2k.affected.TaskPlanner
import java.io.File
import kotlin.coroutines.coroutineContext

class AffectedToolset : McpToolset {

    override fun isEnabled(): Boolean = true

    @McpTool
    @McpDescription(
        "Lists the modules affected by the current changes: modules whose files changed, " +
            "and whether their public API changed. Use before running tests to know the minimal scope."
    )
    suspend fun affected_modules(): String {
        val project = coroutineContext.project
        val state = project.service<AffectedState>()
        val modules = state.modules

        if (modules.isEmpty()) return "No affected modules."

        return buildString {
            appendLine("Affected modules: ${modules.size}")
            modules.sortedBy { it.id }.forEach { module ->
                append("  ${module.id}")
                if (!module.hasTests) append("  (no tests)")
                appendLine()
            }
        }
    }

    @McpTool
    @McpDescription(
        "Returns the exact Gradle tasks that verify the current changes: unit tests of changed " +
            "modules plus compilation of modules consuming a changed public API."
    )
    suspend fun affected_verification_plan(): String {
        val project = coroutineContext.project
        val projectDir = project.basePath?.let(::File) ?: return "Project has no base path."

        val changes = ChangeAnalyzer(projectDir, AffectedSettings.getInstance().baseBranch).collect()
        if (changes.files.isEmpty()) return "No source changes."

        val plan = readAction {
            val graph = ModuleGraph(project)
            val changed = changes.files.mapNotNull { graph.nodeFor(it) }.distinct()
            val apiNodes = changes.apiTouched.mapNotNull { graph.nodeFor(it) }.toSet()
            val consumers = if (apiNodes.isEmpty()) emptyList() else graph.directDependents(apiNodes)
            TaskPlanner.plan(changed.map { it.info() }, consumers.map { it.info() })
        }

        if (plan.isEmpty) return "Nothing to verify."

        return buildString {
            appendLine("Modules to test: ${plan.tested}, consumers to compile: ${plan.compiled}")
            plan.groups.forEach { (_, root, tasks) ->
                appendLine("In $root:")
                tasks.forEach { appendLine("  $it") }
            }
        }
    }

    @McpTool
    @McpDescription(
        "Lists files changed against the base branch, marking those that change public API. " +
            "A public API change is what can break other modules."
    )
    suspend fun affected_changed_files(): String {
        val project = coroutineContext.project
        val projectDir = project.basePath?.let(::File) ?: return "Project has no base path."

        val changes = ChangeAnalyzer(projectDir, AffectedSettings.getInstance().baseBranch).collect()
        if (changes.files.isEmpty()) return "No source changes."

        return buildString {
            appendLine("Changed files: ${changes.files.size}, of them API-changing: ${changes.apiTouched.size}")
            changes.files.sortedBy { it.path }.forEach { file ->
                val relative = file.relativeTo(projectDir).invariantSeparatorsPath
                appendLine(if (file in changes.apiTouched) "  [API] $relative" else "  $relative")
            }
        }
    }

    @McpTool
    @McpDescription(
        "Runs the verification for current changes: unit tests of changed modules and, when their " +
            "public API changed, compilation of the modules consuming them. Tasks run through the IDE " +
            "Gradle integration; results appear in the Run tool window."
    )
    suspend fun affected_run_verification(): String {
        val project = coroutineContext.project
        val projectDir = project.basePath?.let(::File) ?: return "Project has no base path."

        val changes = ChangeAnalyzer(projectDir, AffectedSettings.getInstance().baseBranch).collect()
        if (changes.files.isEmpty()) return "No source changes; nothing to run."

        val plan = readAction {
            val graph = ModuleGraph(project)
            val changed = changes.files.mapNotNull { graph.nodeFor(it) }.distinct()
            val apiNodes = changes.apiTouched.mapNotNull { graph.nodeFor(it) }.toSet()
            val consumers = if (apiNodes.isEmpty()) emptyList() else graph.directDependents(apiNodes)
            TaskPlanner.plan(changed.map { it.info() }, consumers.map { it.info() })
        }
        if (plan.isEmpty) return "Nothing to run."

        plan.groups.forEach { runTasks(project, it) }

        return buildString {
            appendLine("Started. Modules tested: ${plan.tested}, consumers compiled: ${plan.compiled}")
            plan.groups.forEach { (_, root, tasks) -> appendLine("$root: ${tasks.joinToString(" ")}") }
        }
    }

    @McpTool
    @McpDescription(
        "Runs a named Gradle task on every affected module that actually declares it, for example " +
            "detekt, lint or koverHtmlReport. Modules without the task are skipped."
    )
    suspend fun affected_run_task(
        @McpDescription("Gradle task name without module path, for example detekt")
        task: String,
    ): String {
        val project = coroutineContext.project
        val modules = project.service<AffectedState>().modules.filter { it.supports(task) }
        if (modules.isEmpty()) return "No affected module declares task '$task'."

        modules.groupBy { Pair(it.systemId, it.buildRoot) }.forEach { (key, group) ->
            runTasks(project, TaskGroup(key.first, key.second, group.map { "${it.id}:$task" }))
        }
        return "Started '$task' on ${modules.size} module(s)."
    }

    private suspend fun runTasks(project: Project, group: TaskGroup) {
        withContext(Dispatchers.EDT) {
            BuildSystems.byId(group.systemId)?.run(project, group.root, group.tasks)
        }
    }

    @McpTool
    @McpDescription(
        "Stops Gradle runs started from the IDE, including verification started by this toolset. " +
            "Returns how many were stopped."
    )
    suspend fun affected_stop(): String {
        val project = coroutineContext.project
        val stopped = withContext(Dispatchers.EDT) {
            val running = runningProcessHandlers(project)
            running.forEach { it.destroyProcess() }
            running.size
        }
        return if (stopped == 0) "Nothing is running." else "Stopped $stopped run(s)."
    }

    @McpTool
    @McpDescription(
        "Reports whether a verification run is currently in progress and how many modules are affected."
    )
    suspend fun affected_status(): String {
        val project = coroutineContext.project
        val state = project.service<AffectedState>()
        val running = withContext(Dispatchers.EDT) {
            runningProcessHandlers(project).size
        }

        return buildString {
            appendLine("Affected modules: ${state.affectedModules}")
            appendLine("Base branch: ${AffectedSettings.getInstance().baseBranch}")
            appendLine("Consumer check: ${if (AffectedSettings.getInstance().checkConsumers) "on" else "off"}")
            appendLine("Running Gradle sessions: $running")
        }
    }

    @McpTool
    @McpDescription(
        "Lists Gradle tasks that exist on the affected modules, so you know what can be run with " +
            "affected_run_task. Useful to discover whether the project has detekt, lint, coverage and so on."
    )
    suspend fun affected_available_tasks(): String {
        val project = coroutineContext.project
        val modules = project.service<AffectedState>().modules
        if (modules.isEmpty()) return "No affected modules."

        val counts = modules
            .flatMap { it.tasks }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

        return buildString {
            appendLine("Tasks available on affected modules:")
            counts.forEach { (task, count) -> appendLine("  $task  (in $count module(s))") }
        }
    }

    @McpTool
    @McpDescription(
        "Changes plugin settings: the base branch changes are compared against, and whether modules " +
            "consuming a changed public API are compiled. Pass only what you want to change."
    )
    suspend fun affected_configure(
        @McpDescription("Base branch, for example develop, main or master")
        baseBranch: String? = null,
        @McpDescription("Whether to compile modules consuming a changed public API")
        checkConsumers: Boolean? = null,
    ): String {
        val settings = AffectedSettings.getInstance()
        baseBranch?.takeIf { it.isNotBlank() }?.let { settings.baseBranch = it }
        checkConsumers?.let { settings.checkConsumers = it }

        val project = coroutineContext.project
        project.service<AffectedState>().invalidate()

        return "Base branch: ${settings.baseBranch}, consumer check: ${if (settings.checkConsumers) "on" else "off"}."
    }

    private fun runningProcessHandlers(project: Project) =
        RunContentManager.getInstance(project).allDescriptors
            .mapNotNull { it.processHandler }
            .filterNot { it.isProcessTerminated || it.isProcessTerminating }
}
