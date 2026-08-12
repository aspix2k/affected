package com.aspix2k.affected

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class VerificationStatus {
    IDLE,
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
) {
    val affectedModules: Int get() = modules.size
}

internal class AffectedStateStore {
    private data class StoredState(
        val revision: Long,
        val analysisStatus: AnalysisStatus,
        val modules: List<AffectedModule>,
        val runningVerifications: Int,
    )

    private val state = AtomicReference(StoredState(0, AnalysisStatus.ANALYZING, emptyList(), 0))

    val currentRevision: Long get() = state.get().revision

    fun snapshot(): AffectedStateSnapshot {
        val current = state.get()
        return AffectedStateSnapshot(
            revision = current.revision,
            analysisStatus = current.analysisStatus,
            modules = current.modules,
            verificationStatus = if (current.runningVerifications == 0) {
                VerificationStatus.IDLE
            } else {
                VerificationStatus.RUNNING
            },
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

    fun complete(expectedRevision: Long, modules: List<AffectedModule>): Boolean {
        while (true) {
            val current = state.get()
            if (current.revision != expectedRevision) return false
            if (state.compareAndSet(
                    current,
                    current.copy(analysisStatus = AnalysisStatus.READY, modules = modules.toList()),
                )
            ) {
                return true
            }
        }
    }

    fun fail(expectedRevision: Long): Boolean {
        while (true) {
            val current = state.get()
            if (current.revision != expectedRevision) return false
            if (state.compareAndSet(
                    current,
                    current.copy(analysisStatus = AnalysisStatus.UNAVAILABLE, modules = emptyList()),
                )
            ) {
                return true
            }
        }
    }

    fun markRunning() {
        update { current -> current.copy(runningVerifications = current.runningVerifications + 1) }
    }

    fun tryMarkRunning(expectedRevision: Long): Boolean {
        while (true) {
            val current = state.get()
            val currentRevision = current.revision == expectedRevision
            val ready = current.analysisStatus == AnalysisStatus.READY && current.modules.isNotEmpty()
            if (!currentRevision || !ready || current.runningVerifications != 0) return false
            if (state.compareAndSet(current, current.copy(runningVerifications = 1))) return true
        }
    }

    fun markFinished() {
        update { current ->
            current.copy(runningVerifications = (current.runningVerifications - 1).coerceAtLeast(0))
        }
    }

    private fun update(transform: (StoredState) -> StoredState) {
        while (true) {
            val current = state.get()
            if (state.compareAndSet(current, transform(current))) return
        }
    }
}

@Service(Service.Level.PROJECT)
class AffectedState(
    private val project: Project,
    private val scope: CoroutineScope,
) {

    private val state = AffectedStateStore()
    private var debounceMs = DEBOUNCE_MS.toLong()
    private var awaitSmart: suspend () -> Unit = {
        DumbService.getInstance(project).state.first { !it.isDumb }
    }
    private var analyzeProject: suspend () -> List<AffectedModule> = { analyze() }

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
        analyzeProject: suspend () -> List<AffectedModule>,
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
    val isRunning: Boolean get() = verificationStatus == VerificationStatus.RUNNING

    fun markRunning() = state.markRunning()

    fun tryMarkRunning(expectedRevision: Long): Boolean = state.tryMarkRunning(expectedRevision)

    fun markFinished() = state.markFinished()

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

    private suspend fun analyze(): List<AffectedModule> = withContext(Dispatchers.Default) {
        val files = ProjectChanges.pathsSuspending(project)
        val graph = ModuleGraph.create(project)

        affectedModules(graph, files)
    }

    private companion object {
        val LOG = logger<AffectedState>()
        const val DEBOUNCE_MS = 1500
    }
}

internal fun affectedModules(graph: ModuleGraph, files: List<java.io.File>): List<AffectedModule> =
    files.flatMap(graph::nodesFor)
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
