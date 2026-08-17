package com.aspix2k.affected.build

import com.aspix2k.affected.AffectedRunSessions
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.idea.maven.execution.MavenRunner
import org.jetbrains.idea.maven.execution.MavenRunnerSettings
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JPanel
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliMavenCancellationConformanceTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testMavenCancellationDuringCollectorPublicationRemovesOwnedOutput() = runBlocking {
        if (System.getProperty(CONFORMANCE_PROPERTY) != "true") return@runBlocking
        val sessions = AffectedRunSessions.getInstance(project)
        val collectorPublished = CompletableDeferred<File>()
        val releaseCollector = CountDownLatch(1)
        val target = File(checkNotNull(project.basePath), "maven-collector-cancellation")
        assertDeleted(target)
        val artifactsDirectory = File(target, "artifacts")
        assertTrue(artifactsDirectory.mkdirs())
        val artifacts = MavenCollectorArtifacts(
            File(artifactsDirectory, "agent.jar").apply { writeText("agent") }.toPath(),
            File(artifactsDirectory, "extension.jar").apply { writeText("extension") }.toPath(),
        )
        var output: File? = null
        val cancelled = async(Dispatchers.Default) {
            MavenBuildSystem(
                collectorFactory = {
                    MavenCollectorRun.create(File(target, "cache").toPath(), artifacts)
                },
                onCollectorPublished = { collector ->
                    collectorPublished.complete(checkNotNull(collector).outputRoot.toFile())
                    releaseCollector.await()
                },
                onLaunchQueued = {},
            ).runAndWaitSuspending(project, checkNotNull(project.basePath), listOf(":validate"))
        }
        try {
            val ownedOutput = withTimeout(PROCESS_TIMEOUT_MILLIS) { collectorPublished.await() }
            output = ownedOutput
            cancelled.cancel()
            releaseCollector.countDown()

            withTimeout(PROCESS_TIMEOUT_MILLIS) {
                assertFailsWith<CancellationException> { cancelled.await() }
            }
            assertEquals(0, sessions.activeCount())
            assertFalse(ownedOutput.exists(), "Cancelled Maven collector output was not removed: $ownedOutput")
        } finally {
            releaseCollector.countDown()
            cancelled.cancel()
            runCatching { withTimeout(PROCESS_TIMEOUT_MILLIS) { cancelled.await() } }
            sessions.stopOwned()
            output?.let(::assertDeleted)
            assertDeleted(target)
        }
    }

    fun testMavenCancellationBeforeLaunchDoesNotStartTask() = runBlocking {
        if (System.getProperty(CONFORMANCE_PROPERTY) != "true") return@runBlocking
        val source = CliConformanceRepository.configured.fixture("maven-cancellation")
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        val target = File(checkNotNull(project.basePath), "maven-cancellation")
        val existingDescriptors = currentDescriptors()
        val existingEditors = currentEditors()
        val sessions = AffectedRunSessions.getInstance(project)
        val runnerSettings = MavenRunner.getInstance(project).settings
        val originalJre = runnerSettings.jreName
        val releaseEdt = CountDownLatch(1)
        val launchQueued = CompletableDeferred<Unit>()
        val collectorPublished = CompletableDeferred<File?>()
        val artifactsDirectory = File(target, "cancellation-artifacts")
        var cancellationDescriptors = emptySet<RunContentDescriptor>()
        var collectorOutput: File? = null
        var cancelled: Deferred<Boolean>? = null
        try {
            runnerSettings.setJreName(MavenRunnerSettings.USE_INTERNAL_JAVA)
            assertDeleted(target)
            assertTrue(source.copyRecursively(target, overwrite = true))
            assertTrue(
                withTimeout(PROCESS_TIMEOUT_MILLIS) {
                    MavenBuildSystem().runAndWaitSuspending(project, target.path, listOf(":validate"))
                },
            )
            assertEquals(0, sessions.activeCount())
            cancellationDescriptors = currentDescriptors()
            assertTrue(artifactsDirectory.mkdirs())
            val artifacts = MavenCollectorArtifacts(
                File(artifactsDirectory, "agent.jar").apply { writeText("agent") }.toPath(),
                File(artifactsDirectory, "extension.jar").apply { writeText("extension") }.toPath(),
            )

            val edtBlocked = CompletableDeferred<Unit>()
            ApplicationManager.getApplication().invokeLater {
                edtBlocked.complete(Unit)
                releaseEdt.await()
            }
            edtBlocked.await()
            cancelled = async(Dispatchers.Default) {
                MavenBuildSystem(
                    collectorFactory = {
                        MavenCollectorRun.create(File(target, "cancellation-cache").toPath(), artifacts)
                    },
                    onCollectorPublished = { collector ->
                        collectorPublished.complete(collector?.outputRoot?.toFile())
                    },
                    onLaunchQueued = { launchQueued.complete(Unit) },
                )
                    .runAndWaitSuspending(project, target.path, listOf(":validate"))
            }
            waitForQueuedLaunch(sessions, cancelled, launchQueued)
            collectorOutput = withTimeout(PROCESS_TIMEOUT_MILLIS) { collectorPublished.await() }
            assertTrue(collectorOutput != null && collectorOutput.isDirectory)

            assertEquals(1, sessions.stopOwned())
            releaseEdt.countDown()

            assertFalse(withTimeout(PROCESS_TIMEOUT_MILLIS) { cancelled.await() })
            assertEquals(0, sessions.activeCount())
            assertFalse(checkNotNull(collectorOutput).exists())
            assertEquals(cancellationDescriptors, currentDescriptors())
        } finally {
            releaseEdt.countDown()
            sessions.stopOwned()
            runnerSettings.setJreName(originalJre)
            cancelled?.let { task -> runCatching { withTimeout(PROCESS_TIMEOUT_MILLIS) { task.await() } } }
            collectorOutput?.let(::assertDeleted)
            ApplicationManager.getApplication().invokeAndWait {
                val manager = RunContentManager.getInstance(project)
                val executor = DefaultRunExecutor.getRunExecutorInstance()
                manager.allDescriptors.filterNot(existingDescriptors::contains)
                    .forEach { manager.removeRunContent(executor, it) }
                val factory = EditorFactory.getInstance()
                factory.allEditors.filterNot(existingEditors::contains).forEach(factory::releaseEditor)
            }
            assertDeleted(target)
        }
    }

    fun testRunningMavenCancellationTerminatesChildrenBeforeCollectorCleanup() = runBlocking {
        val sessions = AffectedRunSessions.getInstance(project)
        val target = File(checkNotNull(project.basePath), "maven-running-cancellation")
        val artifactsDirectory = File(target, "artifacts")
        val ready = File(target, "child.ready")
        val pid = File(target, "child.pid")
        val collectorOutput = AtomicReference<File>()
        val rootProcess = AtomicReference<Process>()
        assertDeleted(target)
        assertTrue(artifactsDirectory.mkdirs())
        val artifacts = MavenCollectorArtifacts(
            File(artifactsDirectory, "agent.jar").apply { writeText("agent") }.toPath(),
            File(artifactsDirectory, "extension.jar").apply { writeText("extension") }.toPath(),
        )
        val running = async(Dispatchers.Default) {
            MavenBuildSystem(
                collectorFactory = {
                    MavenCollectorRun.create(File(target, "cache").toPath(), artifacts)
                },
                onCollectorPublished = { collector ->
                    collectorOutput.set(checkNotNull(collector).outputRoot.toFile())
                },
                onLaunchQueued = {},
                runConfiguration = { _, _, callback ->
                    val process = javaProcess(
                        MavenProcessTree::class.java.name,
                        "parent",
                        collectorOutput.get().path,
                        pid.path,
                        ready.path,
                    )
                    rootProcess.set(process)
                    val handler = com.intellij.execution.process.OSProcessHandler(process, "Maven process tree")
                    callback?.processStarted(RunContentDescriptor(null, handler, JPanel(), "Maven"))
                    handler.startNotify()
                },
            ).runAndWaitSuspending(project, target.path, listOf(":validate"))
        }
        var child: ProcessHandle? = null
        try {
            withTimeout(PROCESS_TIMEOUT_MILLIS) {
                while (!ready.isFile) {
                    check(!running.isCompleted) { "The Maven fixture ended before publishing its child" }
                    delay(25)
                }
            }
            child = ProcessHandle.of(pid.readText().trim().toLong()).orElseThrow()
            assertTrue(child.isAlive)
            assertTrue(collectorOutput.get().isDirectory)

            assertEquals(1, sessions.stopOwned())

            assertFalse(withTimeout(PROCESS_TIMEOUT_MILLIS) { running.await() })
            assertFalse(child.isAlive)
            assertFalse(collectorOutput.get().exists())
            assertEquals(0, sessions.activeCount())
        } finally {
            sessions.stopOwned()
            runCatching { withTimeout(PROCESS_TIMEOUT_MILLIS) { running.await() } }
            child?.takeIf(ProcessHandle::isAlive)?.destroyForcibly()
            rootProcess.get()?.takeIf(Process::isAlive)?.destroyForcibly()
            assertDeleted(target)
        }
    }

    private suspend fun waitForQueuedLaunch(
        sessions: AffectedRunSessions,
        task: Deferred<Boolean>,
        launchQueued: Deferred<Unit>,
    ) {
        withTimeout(PROCESS_TIMEOUT_MILLIS) {
            launchQueued.await()
            check(!task.isCompleted) {
                "Maven task finished before its queued launch could be stopped: ${task.await()}"
            }
            while (sessions.activeCount() == 0) delay(25)
        }
    }

    private fun currentDescriptors(): Set<RunContentDescriptor> {
        var descriptors = emptySet<RunContentDescriptor>()
        ApplicationManager.getApplication().invokeAndWait {
            descriptors = RunContentManager.getInstance(project).allDescriptors.toSet()
        }
        return descriptors
    }

    private fun currentEditors(): Set<Editor> {
        var editors = emptySet<Editor>()
        ApplicationManager.getApplication().invokeAndWait {
            editors = EditorFactory.getInstance().allEditors.toSet()
        }
        return editors
    }

    private fun assertDeleted(file: File) {
        assertTrue(!file.exists() || file.deleteRecursively(), "Failed to delete $file")
        assertFalse(file.exists(), "Cleanup left $file")
    }

    private fun javaProcess(mainClass: String, vararg arguments: String): Process {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            check(mainClass == MavenProcessTree::class.java.name)
            return ProcessBuilder(
                "/bin/sh",
                "-c",
                "sleep 60 & child=${'$'}!; printf %s \"${'$'}child\" > \"${'$'}1\"; " +
                    ": > \"${'$'}2\"; wait \"${'$'}child\"",
                "affected",
                arguments[2],
                arguments[3],
            ).start()
        }
        return ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java.exe").path,
            "-cp",
            System.getProperty("java.class.path"),
            mainClass,
            *arguments,
        ).start()
    }

    private companion object {
        const val CONFORMANCE_PROPERTY = "affected.cliConformance"
        const val PROCESS_TIMEOUT_MILLIS = 120_000L
    }
}

private object MavenProcessTree {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val output = File(arguments[1])
        val pid = File(arguments[2])
        val ready = File(arguments[3])
        if (arguments[0] == "child") {
            FileChannel.open(
                File(output, "locked.txt").toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use {
                pid.writeText(ProcessHandle.current().pid().toString())
                ready.writeText("ready")
                Thread.sleep(TimeUnit.MINUTES.toMillis(1))
            }
            return
        }
        val child = ProcessBuilder(
            ProcessHandle.current().info().command().orElseThrow(),
            "-cp",
            System.getProperty("java.class.path"),
            MavenProcessTree::class.java.name,
            "child",
            output.path,
            pid.path,
            ready.path,
        ).start()
        child.waitFor()
    }
}
