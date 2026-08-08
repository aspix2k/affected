package com.aspix2k.affected

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val recountJob = AtomicReference<Job?>()
    private val verificationLock = Any()
    private var runningVerifications = 0

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
        val nextJob = scope.launch(start = CoroutineStart.LAZY) {
            delay(DEBOUNCE_MS.toLong())
            recount()
        }
        recountJob.getAndSet(nextJob)?.cancel()
        nextJob.start()
    }

    private suspend fun recount() {
        modules = withContext(Dispatchers.IO) {
            val files = ProjectChanges.paths(project)
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
                    )
                }
        }
        ready = true
    }

    private companion object {
        const val DEBOUNCE_MS = 1500
    }
}
