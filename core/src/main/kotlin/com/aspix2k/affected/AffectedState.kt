package com.aspix2k.affected

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

enum class VerificationStatus {
    IDLE,
    RUNNING,
}

@Service(Service.Level.PROJECT)
class AffectedState(
    private val project: Project,
    private val scope: CoroutineScope,
) {

    @Volatile
    var modules: List<AffectedModule> = emptyList()
        private set

    val affectedModules: Int get() = modules.size

    @Volatile
    var ready: Boolean = false
        private set

    private val status = AtomicReference(VerificationStatus.IDLE)
    private val invalidations = Channel<Unit>(Channel.CONFLATED)
    private val verificationLock = Any()
    private var runningVerifications = 0

    init {
        scope.launch {
            while (true) {
                invalidations.receive()
                do {
                    delay(DEBOUNCE_MS.toLong())
                } while (invalidations.tryReceive().isSuccess)
                refresh()
            }
        }
    }

    val verificationStatus: VerificationStatus get() = status.get()
    val isRunning: Boolean get() = verificationStatus == VerificationStatus.RUNNING

    fun markRunning() {
        synchronized(verificationLock) {
            runningVerifications++
            status.set(VerificationStatus.RUNNING)
        }
    }

    fun markFinished() {
        synchronized(verificationLock) {
            runningVerifications = (runningVerifications - 1).coerceAtLeast(0)
            if (runningVerifications == 0) status.set(VerificationStatus.IDLE)
        }
    }

    fun invalidate() {
        invalidations.trySend(Unit)
    }

    private suspend fun refresh() {
        try {
            modules = analyze()
            ready = true
        } catch (error: CancellationException) {
            throw error
        } catch (error: ProcessCanceledException) {
            throw error
        } catch (error: Exception) {
            modules = emptyList()
            ready = true
            LOG.warn("Failed to refresh affected modules", error)
        }
    }

    private suspend fun analyze(): List<AffectedModule> = withContext(Dispatchers.Default) {
        val files = ProjectChanges.pathsSuspending(project)
        val graph = ModuleGraph.create(project)

        files.mapNotNull { graph.nodeFor(it) }
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
    }

    private companion object {
        val LOG = logger<AffectedState>()
        const val DEBOUNCE_MS = 1500
    }
}
