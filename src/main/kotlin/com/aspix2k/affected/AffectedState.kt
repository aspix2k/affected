package com.aspix2k.affected

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class AffectedState(private val project: Project) {

    @Volatile
    var modules: List<AffectedModule> = emptyList()
        private set

    val affectedModules: Int get() = modules.size

    @Volatile
    var ready: Boolean = false
        private set

    private val running = AtomicBoolean(false)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)

    val isRunning: Boolean get() = running.get()

    fun markRunning(value: Boolean) {
        running.set(value)
    }

    fun invalidate() {
        alarm.cancelAllRequests()
        alarm.addRequest(::recount, DEBOUNCE_MS)
    }

    private fun recount() {
        val projectDir = project.basePath?.let(::File) ?: return
        val settings = AffectedSettings.getInstance()
        val files = ChangeAnalyzer(projectDir, settings.baseBranch).collectPaths()

        val graph = ModuleGraph(project)
        val tasks = GradleTasks(project)
        modules = ApplicationManager.getApplication().runReadAction<List<AffectedModule>> {
            files.mapNotNull { graph.nodeFor(it) }
                .distinct()
                .mapNotNull { node ->
                    val directory = node.sourceRoot ?: node.contentRoots.firstOrNull()?.path
                    if (directory == null) return@mapNotNull null
                    AffectedModule(
                        gradlePath = node.gradlePath,
                        buildRoot = node.buildRoot,
                        directory = directory,
                        testDirectory = node.testRoot,
                        isAndroid = node.isAndroid,
                        hasTests = node.hasTests,
                        tasks = tasks.namesFor(directory),
                    )
                }
        }
        ready = true
    }

    private companion object {
        const val DEBOUNCE_MS = 1500
    }
}
