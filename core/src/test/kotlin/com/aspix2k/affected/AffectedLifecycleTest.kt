package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.BuildSystem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class AffectedLifecycleTest : BasePlatformTestCase() {

    private var registeredPoint = false

    override fun setUp() {
        super.setUp()
        val area = ApplicationManager.getApplication().extensionArea
        if (!area.hasExtensionPoint(BUILD_SYSTEM_POINT)) {
            area.registerExtensionPoint(
                BUILD_SYSTEM_POINT.name,
                BuildSystem::class.java.name,
                ExtensionPoint.Kind.INTERFACE,
                true,
            )
            registeredPoint = true
        }
    }

    override fun tearDown() {
        try {
            super.tearDown()
        } finally {
            if (registeredPoint) {
                ApplicationManager.getApplication().extensionArea.unregisterExtensionPoint(BUILD_SYSTEM_POINT.name)
            }
        }
    }

    override fun runInDispatchThread(): Boolean = false

    fun testPublishedPlanRunsThroughTheActionPathAndStopCancelsBeforeTheNextClaim() = runBlocking {
        val root = File(requireNotNull(project.basePath))
        val source = File(root, "src/Main.kt").apply {
            parentFile.mkdirs()
            writeText("class Main\n")
        }
        val adapter = LifecycleBuildSystem(root)
        ExtensionTestUtil.maskExtensions(BUILD_SYSTEM_POINT, listOf(adapter), testRootDisposable)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sessions = AffectedRunSessions.getInstance(project)
        val analysis = publishedAnalysis(root, source)
        val state = AffectedState(
            project = project,
            scope = scope,
            debounceMs = 0,
            awaitSmart = {},
            analyzeProject = { analysis },
        )
        try {
            state.invalidate()
            withTimeout(5_000) {
                while (state.snapshot().analysisStatus != AnalysisStatus.READY) {
                    delay(10)
                }
            }

            val snapshot = state.snapshot()
            assertEquals(AnalysisStatus.READY, snapshot.analysisStatus)
            assertEquals(1, snapshot.affectedModules)
            assertEquals(listOf("LIFECYCLE"), snapshot.plans?.testsOnly?.plan?.groups?.map { it.systemId })

            val job = requireNotNull(startClaimedAffectedRun(project, state, { scope }))
            withTimeout(5_000) { adapter.started.await() }
            assertEquals(VerificationStatus.RUNNING, state.snapshot().verificationStatus)
            assertEquals(null, state.tryClaimReadyRun())

            assertEquals(1, sessions.stopOwned())
            withTimeout(5_000) { adapter.stopped.await() }
            assertTrue(adapter.cancelled.get())
            withTimeout(5_000) { job.join() }
            assertEquals(VerificationStatus.IDLE, state.snapshot().verificationStatus)
            assertEquals(0, sessions.activeCount())

            val next = requireNotNull(state.tryClaimReadyRun())
            next.close()
        } finally {
            sessions.stopOwned()
            scope.cancel()
        }
    }

    private fun publishedAnalysis(root: File, source: File): AffectedAnalysis {
        val rootPath = root.canonicalFile.path
        val module = BuildModule(
            id = "lifecycle",
            root = rootPath,
            contentRoots = listOf(rootPath),
            testTask = "wait",
            compileTask = null,
            hasTests = true,
            executionId = ".",
        )
        val group = TaskGroup("LIFECYCLE", rootPath, listOf(".:wait"))
        val prepared = Verification.Prepared(
            Plan(listOf(group), tested = 1, compiled = 0),
            BuildChanges(listOf(source.canonicalFile.path), emptySet(), comparedToBase = true),
        )
        return AffectedAnalysis(
            modules = listOf(
                AffectedModule(
                    id = module.id,
                    systemId = "LIFECYCLE",
                    buildRoot = module.root,
                    directory = module.root,
                    testDirectory = null,
                    testTask = module.testTask,
                    compileTask = null,
                    hasTests = true,
                    tasks = emptySet(),
                    executionRoot = module.root,
                    executionId = module.executionId,
                ),
            ),
            changes = ProjectChanges.Result(
                files = listOf(source),
                apiTouched = emptySet(),
                exactSelectionEligible = setOf(source),
                comparedToBase = true,
            ),
            plans = Verification.PreparedPlans(prepared, prepared),
        )
    }

    private class LifecycleBuildSystem(
        private val root: File,
    ) : com.aspix2k.affected.build.SuspendingBuildSystem {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)

        override val id: String = "LIFECYCLE"
        override val sourceExtensions: Set<String> = setOf("kt")
        override fun isPresent(project: Project): Boolean = true
        override fun modules(project: Project): List<BuildModule> {
            val rootPath = root.canonicalFile.path
            return listOf(
                BuildModule(
                    id = "lifecycle",
                    root = rootPath,
                    contentRoots = listOf(rootPath),
                    testTask = "wait",
                    compileTask = null,
                    hasTests = true,
                    executionId = ".",
                ),
            )
        }
        override fun run(project: Project, root: String, tasks: List<String>) = Unit
        override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean {
            started.complete(Unit)
            return try {
                awaitCancellation()
            } finally {
                cancelled.set(true)
                stopped.complete(Unit)
            }
        }
    }

    private companion object {
        val BUILD_SYSTEM_POINT = ExtensionPointName.create<BuildSystem>("com.aspix2k.affected.buildSystem")
    }
}
