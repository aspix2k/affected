package com.aspix2k.affected

import com.aspix2k.affected.build.BuildSystems
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
        val files = ChangeAnalyzer(projectDir, settings.baseBranch, BuildSystems.sourceExtensions(project)).collectPaths()

        val graph = ModuleGraph(project)
        modules = ApplicationManager.getApplication().runReadAction<List<AffectedModule>> {
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
