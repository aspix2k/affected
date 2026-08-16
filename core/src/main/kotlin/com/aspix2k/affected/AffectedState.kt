package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

enum class VerificationStatus {
    IDLE,
    PREPARING,
    RUNNING,
}

enum class AnalysisStatus {
    ANALYZING,
    READY,
    UNAVAILABLE,
}

data class AffectedStateSnapshot(
    val revision: Long,
    val analysisStatus: AnalysisStatus,
    val modules: List<AffectedModule>,
    val verificationStatus: VerificationStatus,
    val changes: ProjectChanges.Result? = null,
    val plans: Verification.PreparedPlans? = null,
) {
    val affectedModules: Int get() = modules.count { it.hasTests }
}

internal data class AffectedAnalysis(
    val modules: List<AffectedModule>,
    val changes: ProjectChanges.Result,
    val plans: Verification.PreparedPlans,
)

internal const val MAX_PUBLISHED_MODULES = 4096

internal class AffectedStateStore {
    private data class StoredState(
        val revision: Long,
        val analysisStatus: AnalysisStatus,
        val analysis: AffectedAnalysis?,
        val verificationStatus: VerificationStatus,
        val runToken: Long?,
    )

    private val state = AtomicReference(
        StoredState(0, AnalysisStatus.ANALYZING, null, VerificationStatus.IDLE, null),
    )
    private val nextRunToken = AtomicLong()

    val currentRevision: Long get() = state.get().revision

    fun snapshot(): AffectedStateSnapshot {
        val current = state.get()
        return AffectedStateSnapshot(
            revision = current.revision,
            analysisStatus = current.analysisStatus,
            modules = current.analysis?.modules.orEmpty(),
            verificationStatus = current.verificationStatus,
            changes = current.analysis?.changes,
            plans = current.analysis?.plans,
        )
    }

    fun invalidate(): Long {
        while (true) {
            val current = state.get()
            val invalidated = current.copy(
                revision = current.revision + 1,
                analysisStatus = AnalysisStatus.ANALYZING,
            )
            if (state.compareAndSet(current, invalidated)) return invalidated.revision
        }
    }

    fun complete(expectedRevision: Long, analysis: AffectedAnalysis): Boolean {
        if (analysis.modules.size > MAX_PUBLISHED_MODULES) {
            fail(expectedRevision)
            return false
        }
        val completed = analysis.snapshot()
        while (true) {
            val current = state.get()
            if (current.revision != expectedRevision) return false
            if (state.compareAndSet(
                    current,
                    current.copy(analysisStatus = AnalysisStatus.READY, analysis = completed),
                )
            ) {
                return true
            }
        }
    }

    fun complete(expectedRevision: Long, modules: List<AffectedModule>): Boolean = complete(
        expectedRevision,
        AffectedAnalysis(modules, EMPTY_CHANGES, EMPTY_PLANS),
    )

    fun fail(expectedRevision: Long): Boolean {
        while (true) {
            val current = state.get()
            if (current.revision != expectedRevision) return false
            if (state.compareAndSet(
                    current,
                    current.copy(analysisStatus = AnalysisStatus.UNAVAILABLE, analysis = null),
                )
            ) {
                return true
            }
        }
    }

    fun tryClaimReadyRun(checkConsumers: Boolean = false): AffectedRunClaim? =
        tryClaimRunning(requireReadyAnalysis = true, checkConsumers)

    fun tryClaimVerification(): AffectedRunClaim? =
        tryClaimRunning(requireReadyAnalysis = false, checkConsumers = false)

    private fun tryClaimRunning(requireReadyAnalysis: Boolean, checkConsumers: Boolean): AffectedRunClaim? {
        val token = nextRunToken.incrementAndGet()
        while (true) {
            val current = state.get()
            val ready = current.analysisStatus == AnalysisStatus.READY &&
                current.analysis?.modules?.isNotEmpty() == true
            if ((requireReadyAnalysis && !ready) || current.verificationStatus != VerificationStatus.IDLE) return null
            val claimed = current.copy(
                verificationStatus = VerificationStatus.PREPARING,
                runToken = token,
            )
            if (state.compareAndSet(current, claimed)) {
                return AffectedRunClaim(
                    snapshot = current.toSnapshot(VerificationStatus.PREPARING),
                    changes = current.analysis?.changes,
                    prepared = current.analysis?.plans?.select(checkConsumers),
                    markRunning = { markRunning(token, current.revision) },
                    release = { markFinished(token) },
                )
            }
        }
    }

    private fun markRunning(token: Long, revision: Long): Boolean {
        while (true) {
            val current = state.get()
            if (current.runToken != token || current.revision != revision) return false
            if (current.verificationStatus == VerificationStatus.RUNNING) return true
            if (current.verificationStatus != VerificationStatus.PREPARING) return false
            if (state.compareAndSet(current, current.copy(verificationStatus = VerificationStatus.RUNNING))) return true
        }
    }

    private fun markFinished(token: Long) {
        while (true) {
            val current = state.get()
            if (current.runToken != token) return
            val finished = current.copy(verificationStatus = VerificationStatus.IDLE, runToken = null)
            if (state.compareAndSet(current, finished)) return
        }
    }

    private fun StoredState.toSnapshot(verificationStatus: VerificationStatus) = AffectedStateSnapshot(
        revision = revision,
        analysisStatus = analysisStatus,
        modules = analysis?.modules.orEmpty(),
        verificationStatus = verificationStatus,
        changes = analysis?.changes,
        plans = analysis?.plans,
    )

    private fun AffectedAnalysis.snapshot() = AffectedAnalysis(
        modules = modules.toList(),
        changes = changes.copy(
            files = changes.files.toList(),
            apiTouched = changes.apiTouched.toSet(),
            exactSelectionEligible = changes.exactSelectionEligible.toSet(),
        ),
        plans = plans,
    )

    private companion object {
        val EMPTY_CHANGES = ProjectChanges.Result(emptyList(), emptySet(), emptySet(), comparedToBase = false)
        val EMPTY_PLANS = Verification.PreparedPlans(
            testsOnly = Verification.Prepared(
                Plan(emptyList(), 0, 0),
                BuildChanges(emptyList(), emptySet(), comparedToBase = false),
            ),
            withConsumers = Verification.Prepared(
                Plan(emptyList(), 0, 0),
                BuildChanges(emptyList(), emptySet(), comparedToBase = false),
            ),
        )
    }
}

class AffectedRunClaim internal constructor(
    val snapshot: AffectedStateSnapshot,
    val changes: ProjectChanges.Result?,
    val prepared: Verification.Prepared?,
    private val markRunning: () -> Boolean,
    private val release: () -> Unit,
) : AutoCloseable, AffectedOwnedSession {
    private val lock = Any()
    private val closed = AtomicBoolean()
    private val groupJobs = HashSet<Job>()
    private var cancellationRequested = false
    private var sessions: AffectedRunSessions? = null

    fun markRunning(): Boolean = synchronized(lock) {
        !closed.get() && !cancellationRequested && markRunning.invoke()
    }

    internal fun bind(sessions: AffectedRunSessions) = synchronized(lock) {
        check(!closed.get())
        check(this.sessions == null)
        this.sessions = sessions
    }

    internal fun isCancellationRequested(): Boolean = synchronized(lock) { cancellationRequested }

    internal fun registerGroupJobs(jobs: Collection<Job>): Boolean = synchronized(lock) {
        if (closed.get() || cancellationRequested) return false
        groupJobs.addAll(jobs)
        true
    }

    internal fun unregisterGroupJobs(jobs: Collection<Job>) = synchronized(lock) {
        groupJobs.removeAll(jobs.toSet())
    }

    override fun isActive(): Boolean = synchronized(lock) { !closed.get() }

    override fun stopIfActive(): Boolean {
        val jobs = synchronized(lock) {
            if (closed.get() || cancellationRequested) return false
            cancellationRequested = true
            groupJobs.toList()
        }
        jobs.forEach(Job::cancel)
        return true
    }

    internal fun complete(passed: Boolean): Boolean {
        val owner = synchronized(lock) { sessions }
        val completion = if (owner == null) {
            synchronized(lock) { completeLocked(passed) }
        } else {
            owner.complete(this, passed)
        }
        if (completion.released) release()
        return completion.passed
    }

    override fun close() {
        complete(passed = false)
    }

    internal fun completeFrom(owner: AffectedRunSessions, passed: Boolean): AffectedRunCompletion = synchronized(lock) {
        if (sessions !== owner) return AffectedRunCompletion(released = false, passed = false)
        sessions = null
        completeLocked(passed)
    }

    private fun completeLocked(passed: Boolean): AffectedRunCompletion {
        if (!closed.compareAndSet(false, true)) return AffectedRunCompletion(released = false, passed = false)
        groupJobs.clear()
        return AffectedRunCompletion(released = true, passed = passed && !cancellationRequested)
    }
}

internal data class AffectedRunCompletion(
    val released: Boolean,
    val passed: Boolean,
)

suspend fun runClaimedGroups(
    claim: AffectedRunClaim,
    groups: List<TaskGroup>,
    context: CoroutineContext,
    run: suspend (TaskGroup) -> Boolean,
): Boolean = coroutineScope {
    val jobs = groups.map { group ->
        async(context, start = CoroutineStart.LAZY) {
            run(group)
        }
    }
    if (!claim.registerGroupJobs(jobs)) {
        jobs.forEach(Job::cancel)
        return@coroutineScope claim.complete(passed = false)
    }
    jobs.forEach(Job::start)
    val passed = try {
        jobs.map { job ->
            try {
                job.await()
            } catch (cancelled: CancellationException) {
                if (!claim.isCancellationRequested()) throw cancelled
                false
            }
        }.all { it }
    } finally {
        claim.unregisterGroupJobs(jobs)
    }
    claim.complete(passed)
}

fun launchClaimed(
    claim: AffectedRunClaim,
    scope: () -> CoroutineScope,
    block: suspend CoroutineScope.() -> Unit,
): Job {
    val job = try {
        scope().launch(block = block)
    } catch (error: Exception) {
        claim.close()
        throw error
    }
    job.invokeOnCompletion { claim.close() }
    return job
}

@Service(Service.Level.PROJECT)
class AffectedState(
    private val project: Project,
    private val scope: CoroutineScope,
) {

    private val state = AffectedStateStore()
    private var debounceMs = DEBOUNCE_MS.toLong()
    private var awaitSmart: suspend () -> Unit = {
        suspendCancellableCoroutine { continuation ->
            DumbService.getInstance(project).runWhenSmart {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
    private var analyzeProject: suspend () -> AffectedAnalysis = { analyze() }

    val modules: List<AffectedModule> get() = snapshot().modules

    val affectedModules: Int get() = snapshot().affectedModules

    val analysisStatus: AnalysisStatus get() = snapshot().analysisStatus
    val ready: Boolean get() = analysisStatus == AnalysisStatus.READY

    private val watchingDumbMode = AtomicBoolean()
    private val invalidations = Channel<Unit>(Channel.CONFLATED)

    internal constructor(
        project: Project,
        scope: CoroutineScope,
        debounceMs: Long,
        awaitSmart: suspend () -> Unit,
        analyzeProject: suspend () -> AffectedAnalysis,
    ) : this(project, scope) {
        this.debounceMs = debounceMs
        this.awaitSmart = awaitSmart
        this.analyzeProject = analyzeProject
    }

    init {
        scope.launch {
            while (true) {
                invalidations.receive()
                do {
                    delay(debounceMs)
                } while (invalidations.tryReceive().isSuccess)
                awaitSmart()
                refresh()
            }
        }
    }

    fun snapshot(): AffectedStateSnapshot = state.snapshot()

    val verificationStatus: VerificationStatus get() = snapshot().verificationStatus
    val isRunning: Boolean get() = verificationStatus != VerificationStatus.IDLE

    fun tryClaimReadyRun(): AffectedRunClaim? = AffectedRunSessions.getInstance(project).claim {
        state.tryClaimReadyRun(AffectedSettings.getInstance().checkConsumers)
    }

    fun tryClaimVerification(): AffectedRunClaim? = AffectedRunSessions.getInstance(project).claim {
        state.tryClaimVerification()
    }

    fun invalidate() {
        state.invalidate()
        invalidations.trySend(Unit)
    }

    fun watchDumbMode() {
        if (!watchingDumbMode.compareAndSet(false, true)) return
        project.messageBus.connect(scope).subscribe(
            DumbService.DUMB_MODE,
            AffectedDumbModeListener(::invalidate),
        )
    }

    private suspend fun refresh() {
        val revision = state.currentRevision
        try {
            state.complete(revision, analyzeProject())
        } catch (error: ProcessCanceledException) {
            if (!project.isDisposed) invalidations.trySend(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (state.fail(revision)) LOG.warn("Failed to refresh affected modules", error)
        }
    }

    private suspend fun analyze(): AffectedAnalysis = withContext(Dispatchers.Default) {
        val changes = ProjectChanges.collectSuspending(project)
        val graph = ModuleGraph.create(project)
        val directOwners = changes.files.associateWith(graph::nodesFor)
        val owners = graph.ownersForChanges(changes.toBuildChanges(), directOwners)

        AffectedAnalysis(
            modules = affectedModules(owners.values.flatten()),
            changes = changes,
            plans = Verification.prepare(graph, changes, directOwners),
        )
    }

    private companion object {
        val LOG = logger<AffectedState>()
        const val DEBOUNCE_MS = 1500
    }
}

internal fun affectedModules(graph: ModuleGraph, files: List<java.io.File>): List<AffectedModule> =
    affectedModules(files.flatMap(graph::nodesFor))

internal fun affectedModules(graph: ModuleGraph, changes: ProjectChanges.Result): List<AffectedModule> =
    affectedModules(graph.ownersForChanges(changes.toBuildChanges()).values.flatten())

private fun affectedModules(nodes: List<ModuleGraph.Node>): List<AffectedModule> =
    nodes
        .distinct()
        .mapNotNull { node ->
            val directory = node.sourceRoot ?: return@mapNotNull null
            AffectedModule(
                id = node.id,
                systemId = node.system.id,
                buildRoot = node.buildRoot,
                directory = directory,
                testDirectory = node.testRoot,
                testTask = node.module.testTask,
                compileTask = node.module.compileTask,
                hasTests = node.hasTests,
                tasks = node.module.extraTasks,
                executionRoot = node.module.executionRoot,
                executionId = node.module.executionId,
            )
        }

internal class AffectedDumbModeListener(
    private val invalidate: () -> Unit,
) : DumbService.DumbModeListener {
    override fun enteredDumbMode() = invalidate()

    override fun exitDumbMode() = invalidate()
}
