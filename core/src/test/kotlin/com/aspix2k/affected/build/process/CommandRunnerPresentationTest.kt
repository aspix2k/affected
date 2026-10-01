package com.aspix2k.affected.build.process

import com.aspix2k.affected.AffectedRunChild
import com.aspix2k.affected.AffectedRunClaim
import com.aspix2k.affected.AffectedRunPresentation
import com.aspix2k.affected.AffectedRunSessions
import com.aspix2k.affected.AffectedRunView
import com.aspix2k.affected.AffectedStateSnapshot
import com.aspix2k.affected.AnalysisStatus
import com.aspix2k.affected.TaskGroup
import com.aspix2k.affected.VerificationStatus
import com.aspix2k.affected.runBoundedBlocking
import com.aspix2k.affected.runClaimedGroupsWithPresentation
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class CommandRunnerPresentationTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testClaimedCliBatchAttachesToTheAggregateWithoutPublishingAChildSession() = runBoundedBlocking {
        val sessions = AffectedRunSessions.getInstance(project)
        val claim = checkNotNull(sessions.claim(::claim))
        val view = RecordingView()
        val presentation = AffectedRunPresentation(claim, view)
        val root = checkNotNull(project.basePath)
        Files.createDirectories(Path.of(root))
        val group = TaskGroup("XCODE", root, listOf(".:build"))
        assertTrue(claim.markRunning())

        val passed = runClaimedGroupsWithPresentation(
            claim,
            listOf(group),
            Dispatchers.Default,
            stopAfterFirstFailure = false,
            presentation,
        ) {
            CommandRunner.runBatchAndWait(
                project,
                root,
                listOf(CliCommand("xcodebuild build", listOf(java(), "-version"))),
                "Affected Xcode",
            )
        }

        val failure = "passed=$passed rootExists=${java.io.File(root).isDirectory} " +
            "active=${sessions.activeCount()} labels=${view.labels}"
        assertTrue(failure, passed)
        assertEquals(listOf("Xcode · ${java.io.File(root).name}"), view.labels)
        assertEquals(1, view.publications)
    }

    fun testFailureWhileShowingTheRunCompletesAsFailedAndReleasesTheSession() = runBoundedBlocking {
        val sessions = AffectedRunSessions.getInstance(project)
        val root = checkNotNull(project.basePath)
        Files.createDirectories(Path.of(root))
        var shown: ProcessHandler? = null

        val passed = withTimeout(10_000) {
            CommandRunner.runBatchAndWait(
                project,
                root,
                listOf(CliCommand("xcodebuild build", listOf(java(), "-version"))),
                "Affected Xcode",
                show = { _, handler, _, _, _ ->
                    shown = handler
                    error("console failed")
                },
            )
        }

        assertFalse(passed)
        assertTrue(checkNotNull(shown).isProcessTerminated)
        assertEquals(0, sessions.activeCount())
    }

    fun testFailureWhileShowingARunningCommandCompletesOnlyAfterTerminationAndOnce() = runBoundedBlocking {
        val sessions = AffectedRunSessions.getInstance(project)
        val root = checkNotNull(project.basePath)
        Files.createDirectories(Path.of(root))
        val marker = Path.of(root, "started")
        val source = Path.of(root, "Sleeper.java")
        Files.writeString(
            source,
            "public class Sleeper { public static void main(String[] a) throws Exception { " +
                "java.nio.file.Files.writeString(java.nio.file.Path.of(a[0]), \"x\"); Thread.sleep(60_000); } }",
        )
        val terminations = AtomicInteger()
        var shown: ProcessHandler? = null

        val passed = withTimeout(30_000) {
            CommandRunner.runBatchAndWait(
                project,
                root,
                listOf(CliCommand("sleeper", listOf(java(), source.toString(), marker.toString()))),
                "Affected Sleeper",
                show = { _, handler, _, _, _ ->
                    shown = handler
                    handler.addProcessListener(object : ProcessListener {
                        override fun processTerminated(event: ProcessEvent) {
                            terminations.incrementAndGet()
                        }
                    })
                    handler.startNotify()
                    val deadline = System.nanoTime() + 20_000_000_000L
                    while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(10)
                    check(Files.exists(marker)) { "The command did not start" }
                    error("console failed")
                },
            )
        }
        val terminatedOnReturn = checkNotNull(shown).isProcessTerminated
        val deadline = System.nanoTime() + 5_000_000_000L
        while (terminations.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)

        assertFalse(passed)
        assertTrue(terminatedOnReturn)
        assertEquals(1, terminations.get())
        assertEquals(0, sessions.activeCount())
    }

    private fun claim() = AffectedRunClaim(
        snapshot = AffectedStateSnapshot(
            revision = 1,
            analysisStatus = AnalysisStatus.READY,
            modules = emptyList(),
            verificationStatus = VerificationStatus.PREPARING,
        ),
        changes = null,
        prepared = null,
        markRunning = { true },
        release = {},
    )

    private fun java(): String = File(
        System.getProperty("java.home"),
        "bin/${if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"}",
    ).path

    private class RecordingView : AffectedRunView {
        var publications = 0
        val labels = mutableListOf<String>()

        override fun publish(handler: ProcessHandler) {
            publications++
            handler.startNotify()
        }

        override fun attach(label: String, child: AffectedRunChild) {
            labels += label
        }

        override fun dispose() = Unit
    }
}
