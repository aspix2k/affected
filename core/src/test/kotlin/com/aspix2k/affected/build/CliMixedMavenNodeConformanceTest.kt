package com.aspix2k.affected.build

import com.aspix2k.affected.AffectedSettings
import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.Plan
import com.aspix2k.affected.ProjectChanges
import com.aspix2k.affected.TaskGroup
import com.aspix2k.affected.Verification
import com.aspix2k.affected.build.maven.MavenBuildSystem
import com.aspix2k.affected.build.node.NodeBuildSystem
import com.aspix2k.affected.runAndWait
import com.aspix2k.affected.runBoundedBlocking
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.common.ThreadLeakTracker
import kotlinx.coroutines.withTimeout
import org.jetbrains.idea.maven.execution.MavenRunner
import org.jetbrains.idea.maven.execution.MavenRunnerSettings
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.server.MavenServerManager
import java.io.File
import java.util.concurrent.TimeUnit

class CliMixedMavenNodeConformanceTest : HeavyPlatformTestCase() {

    private var registeredPoint = false
    private var previousStopAfterFirstFailure = false
    private var previousMavenJre: String? = null
    private var consoleOutput = emptyList<String>()

    override fun setUp() {
        super.setUp()
        ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(), *IDE_IMPORT_THREADS)
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
        ExtensionTestUtil.maskExtensions(
            BUILD_SYSTEM_POINT,
            listOf(MavenBuildSystem(), NodeBuildSystem()),
            testRootDisposable,
        )
        previousStopAfterFirstFailure = AffectedSettings.getInstance().stopAfterFirstFailure
        AffectedSettings.getInstance().stopAfterFirstFailure = false
        val runnerSettings = MavenRunner.getInstance(project).settings
        previousMavenJre = runnerSettings.jreName
        runnerSettings.setJreName(MavenRunnerSettings.USE_INTERNAL_JAVA)
        deleteCopiedRoots()
    }

    override fun tearDown() {
        try {
            unlinkMavenProjects()
            MavenServerManager.getInstance().closeAllConnectorsAndWait()
            deleteCopiedRoots()
            AffectedSettings.getInstance().stopAfterFirstFailure = previousStopAfterFirstFailure
            previousMavenJre?.let { MavenRunner.getInstance(project).settings.setJreName(it) }
            super.tearDown()
        } finally {
            if (registeredPoint) {
                ApplicationManager.getApplication().extensionArea.unregisterExtensionPoint(BUILD_SYSTEM_POINT.name)
            }
        }
    }

    override fun runInDispatchThread(): Boolean = false

    fun testMavenChangeDoesNotOwnTheSiblingNodeProject() = runBoundedBlocking {
        val root = mixedRepo()
        val owners = ModuleGraph.create(project).nodesFor(File(root, MAVEN_SOURCE))
        assertEquals(listOf("MAVEN"), owners.map { it.system.id }.distinct())
        assertTrue(owners.none { it.system.id == "NODE" })
    }

    fun testNodeChangeDoesNotOwnTheSiblingMavenProject() = runBoundedBlocking {
        val root = mixedRepo()
        val owners = ModuleGraph.create(project).nodesFor(File(root, NODE_SOURCE))
        assertEquals(listOf("NODE"), owners.map { it.system.id }.distinct())
        assertTrue(owners.none { it.system.id == "MAVEN" })
    }

    fun testNodeChangePlansOnlyTheNodeGroup() = runBoundedBlocking {
        val root = mixedRepo()
        val prepared = prepared(root, NODE_SOURCE)
        assertEquals(listOf("NODE"), prepared.plan.groups.map { it.systemId }.distinct())
        assertEquals(listOf(".:test"), prepared.plan.groups.single().tasks)
    }

    fun testProductionRegistrySeesBothAdaptersAndPlansTheNodeSide() = runBoundedBlocking {
        val root = mixedRepo()
        val prepared = prepared(root, MAVEN_SOURCE, NODE_SOURCE)
        assertEquals(setOf("MAVEN", "NODE"), BuildSystems.of(project).map { it.id }.toSet())
        assertTrue(prepared.plan.groups.any { it.systemId == "NODE" && it.tasks == listOf(".:test") })
    }

    fun testSimultaneousChangesRunBothGroupsInOneVerificationSession() {
        if (!nativeEnabled()) return
        val root = mixedRepo()
        val outcome = runBoundedBlocking { runBothGroups(root) }
        assertTrue("mixed Maven+Node verification failed${diagnostics()}", outcome.passed)
        assertEquals(setOf("MAVEN", "NODE"), outcome.plan.groups.map { it.systemId }.toSet())
        assertTrue("Maven marker was not written${diagnostics()}", File(root, "backend/mixed-maven.marker").isFile)
        assertTrue("Node marker was not written${diagnostics()}", File(root, "frontend/mixed-node.marker").isFile)
    }

    fun testOneFailingGroupPreservesAggregateFailureAfterBothGroupsRan() {
        if (!nativeEnabled()) return
        val root = mixedRepo()
        File(root, NODE_SOURCE).appendText("\nthrow new Error('requested mixed fixture failure');\n")
        val outcome = runBoundedBlocking { runBothGroups(root) }
        assertFalse(outcome.passed)
        assertTrue(
            "Maven group did not finish after the Node failure${diagnostics()}",
            File(root, "backend/mixed-maven.marker").isFile,
        )
    }

    private suspend fun prepared(root: File, vararg paths: String): Verification.Prepared {
        val files = paths.map { File(root, it) }
        val changes = ProjectChanges.Result(
            files = files,
            apiTouched = emptySet(),
            exactSelectionEligible = files.toSet(),
            comparedToBase = true,
        )
        return Verification.prepare(ModuleGraph.create(project), changes).testsOnly
    }

    private suspend fun runBothGroups(root: File): Verification.Outcome {
        val editors = currentEditors()
        return try {
            withTimeout(SESSION_TIMEOUT_MILLIS) {
                Verification.runAndWait(
                    project,
                    Plan(
                        groups = listOf(
                            TaskGroup("MAVEN", File(root, "backend").path, listOf(":test")),
                            TaskGroup("NODE", File(root, "frontend").path, listOf(".:test")),
                        ),
                        tested = 2,
                        compiled = 0,
                    ),
                )
            }
        } finally {
            disposeRunContents(editors)
        }
    }

    private fun mixedRepo(): File {
        unlinkMavenProjects()
        deleteCopiedRoots()
        val source = CliConformanceRepository.configured.fixture("mixed-maven-node")
        val root = File(requireNotNull(project.basePath))
        source.listFiles().orEmpty().forEach { child ->
            check(child.copyRecursively(File(root, child.name), overwrite = true))
        }
        importMaven(File(root, "backend/pom.xml"))
        return root
    }

    private fun importMaven(pom: File) {
        val virtual = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(pom)) {
            "Maven pom is not on the local filesystem: $pom"
        }
        check(virtual.toNioPath().toFile().canonicalFile == pom.canonicalFile)
        val manager = MavenProjectsManager.getInstance(project)
        ApplicationManager.getApplication().invokeAndWait {
            manager.addManagedFiles(listOf(virtual))
        }
        val expected = pom.parentFile.canonicalPath
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(IMPORT_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (manager.isMavenizedProject &&
                manager.projects.any { File(it.directory).canonicalPath == expected }
            ) {
                return
            }
            Thread.sleep(50)
        }
        error("Maven import did not complete for $pom")
    }

    private fun unlinkMavenProjects() {
        val manager = MavenProjectsManager.getInstanceIfCreated(project) ?: return
        val files = manager.projectsFiles
        if (files.isEmpty()) return
        ApplicationManager.getApplication().invokeAndWait {
            manager.removeManagedFiles(files)
        }
    }

    private fun deleteCopiedRoots() {
        val root = project.basePath?.let(::File) ?: return
        COPIED_ROOTS.forEach { name ->
            val copied = File(root, name)
            if (copied.exists()) {
                check(copied.deleteRecursively())
            }
        }
    }

    private fun nativeEnabled(): Boolean = System.getProperty("affected.cliConformance") == "true"

    private fun currentEditors(): Set<Editor> {
        var editors = emptySet<Editor>()
        ApplicationManager.getApplication().invokeAndWait {
            editors = EditorFactory.getInstance().allEditors.toSet()
        }
        return editors
    }

    private fun disposeRunContents(existingEditors: Set<Editor>) {
        ApplicationManager.getApplication().invokeAndWait {
            val manager = RunContentManager.getInstanceIfCreated(project)
            if (manager != null) {
                val executor = DefaultRunExecutor.getRunExecutorInstance()
                manager.allDescriptors.toList().forEach { descriptor ->
                    manager.removeRunContent(executor, descriptor)
                }
            }
            val factory = EditorFactory.getInstance()
            val created = factory.allEditors.filterNot(existingEditors::contains)
            consoleOutput = created.map { it.document.text.takeLast(OUTPUT_TAIL_CHARS) }.filter(String::isNotBlank)
            created.forEach(factory::releaseEditor)
        }
    }

    private fun diagnostics(): String {
        val memory = File("/proc/meminfo").takeIf(File::isFile)
            ?.readLines()
            ?.firstOrNull { it.startsWith("MemAvailable") }
            .orEmpty()
        return "\n${consoleOutput.joinToString("\n---\n")}\n$memory"
    }

    private companion object {
        val BUILD_SYSTEM_POINT = ExtensionPointName.create<BuildSystem>("com.aspix2k.affected.buildSystem")
        const val MAVEN_SOURCE = "backend/src/main/java/backend/Value.java"
        const val NODE_SOURCE = "frontend/alpha.test.js"
        const val SESSION_TIMEOUT_MILLIS = 300_000L
        const val IMPORT_TIMEOUT_SECONDS = 60L
        const val OUTPUT_TAIL_CHARS = 2_000
        val IDE_IMPORT_THREADS = arrayOf("RemoteMavenServer", "BuildOutputInstantReaderImpl")
        val COPIED_ROOTS = listOf("backend", "frontend")
    }
}
