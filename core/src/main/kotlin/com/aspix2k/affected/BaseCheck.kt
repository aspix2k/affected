package com.aspix2k.affected

import com.aspix2k.affected.build.BaseRuntimeBuildSystem
import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.BuildSystems
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

object BaseCheck {

    private val LOG = logger<BaseCheck>()
    private val RUN_TIMEOUT = 30.minutes
    private val SUPPORTED_SYSTEMS = setOf("GRADLE", "MAVEN", "GO", "CARGO")
    private const val CACHE_DIRECTORY = "affected"

    private class BaseContext(
        val project: Project,
        val projectRoot: String,
        val baseRoot: String,
        val changes: BuildChanges,
        val graph: ModuleGraph,
    )

    suspend fun run(project: Project): BaseCheckReport {
        val record = project.service<AffectedState>().lastVerification?.takeIf { it.failed.isNotEmpty() }
            ?: return BaseCheckReport(blocker = BaseCheckBlocker.NO_FAILED_VERIFICATION)
        return run(project, record)
    }

    suspend fun run(project: Project, record: VerificationRecord): BaseCheckReport {
        val notStarted = BaseCheckReport(blocker = BaseCheckBlocker.NOT_STARTED)
        val projectRoot = project.basePath
        if (projectRoot == null || projectBusy(project)) return notStarted
        val claim = project.service<AffectedState>().tryClaimVerification() ?: return notStarted
        try {
            if (!claim.markRunning()) return notStarted
            val commit = record.changes.baseCommit
            val baseRoot = commit?.let { checkout(project, projectRoot, it) }
            val context = baseRoot?.let {
                BaseContext(
                    project,
                    projectRoot,
                    it,
                    record.changes.translatedToBase(projectRoot, it),
                    withContext(Dispatchers.Default) { ModuleGraph.create(project) },
                )
            }
            val results = ConcurrentHashMap<TaskGroup, BaseRun>()
            if (context != null) {
                runClaimedGroups(claim, record.failed, Dispatchers.IO, stopAfterFirstFailure = false) { group ->
                    val run = runOnBase(group, context, claim)
                    results[group] = run
                    run == BaseRun.Finished(passed = true)
                }
            }
            val unrun = BaseRun.Skipped(if (commit == null) BaseNotRun.NO_BASE_COMMIT else BaseNotRun.CHECKOUT_FAILED)
            return BaseCheckReport(
                verdicts = record.failed.map { group ->
                    BaseGroupVerdict(
                        group.systemId,
                        relativeToProject(group.root, projectRoot),
                        group.tasks,
                        results[group] ?: if (context == null) unrun else BaseRun.Skipped(BaseNotRun.STOPPED),
                    )
                },
                baseCommit = commit,
                baseBranch = record.changes.baseBranch,
            )
        } finally {
            claim.close()
        }
    }

    private suspend fun checkout(project: Project, projectRoot: String, commit: String): String? = try {
        val cache = PathManager.getSystemDir().resolve(CACHE_DIRECTORY).resolve(project.locationHash)
        runInterruptible(Dispatchers.IO) {
            BaseCheckout(File(projectRoot), cache, environment = EnvironmentUtil.getEnvironmentMap())
                .prepare(commit)
                .toString()
        }
    } catch (failure: BaseCheckout.Failure) {
        LOG.warn("Could not prepare the base checkout", failure)
        null
    }

    private suspend fun runOnBase(group: TaskGroup, context: BaseContext, claim: AffectedRunClaim): BaseRun {
        if (group.systemId !in SUPPORTED_SYSTEMS) return BaseRun.Skipped(BaseNotRun.UNSUPPORTED_SYSTEM)
        val baseGroup = translateToBase(group.root, context.projectRoot, context.baseRoot)
            ?.takeIf { File(it).isDirectory }
            ?.let { TaskGroup(group.systemId, it, group.tasks) }
        if (baseGroup == null || missingOnBase(group, context)) return BaseRun.Skipped(BaseNotRun.MODULE_MISSING)
        val runtime = BuildSystems.byId(group.systemId) as? BaseRuntimeBuildSystem
        if (runtime?.sameRuntime(context.project, group.root, baseGroup.root) == false) {
            return BaseRun.Skipped(BaseNotRun.GRADLE_JVM_DIFFERS)
        }
        var accepted = false
        val passed = withTimeoutOrNull(RUN_TIMEOUT) {
            baseGroup.runInPlannedExecutionRoot(Path.of(context.baseRoot), onInvalid = {}) {
                accepted = true
                withContext(BaseCheckRun(Path.of(context.baseRoot))) {
                    runBuildTasks(context.project, group.systemId, baseGroup.root, group.tasks, context.changes)
                }
            }
        }
        return when {
            claim.isCancellationRequested() -> BaseRun.Skipped(BaseNotRun.STOPPED)
            passed == null -> BaseRun.Skipped(BaseNotRun.TIMED_OUT)
            !accepted -> BaseRun.Skipped(BaseNotRun.MODULE_MISSING)
            else -> BaseRun.Finished(passed)
        }
    }

    private fun missingOnBase(group: TaskGroup, context: BaseContext): Boolean {
        val executionIds = group.tasks.mapTo(HashSet()) { it.substringBeforeLast(':') }
        return context.graph.executionNodes(group.systemId, group.root)
            .filter { it.module.executionId in executionIds }
            .any { node ->
                val onBase = node.sourceRoot?.let { translateToBase(it, context.projectRoot, context.baseRoot) }
                onBase != null && !File(onBase).isDirectory
            }
    }
}
