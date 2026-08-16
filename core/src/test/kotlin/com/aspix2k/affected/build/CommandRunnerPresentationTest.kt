package com.aspix2k.affected.build

import com.aspix2k.affected.AffectedRunChild
import com.aspix2k.affected.AffectedRunClaim
import com.aspix2k.affected.AffectedRunPresentation
import com.aspix2k.affected.AffectedRunSessions
import com.aspix2k.affected.AffectedRunView
import com.aspix2k.affected.AffectedStateSnapshot
import com.aspix2k.affected.AnalysisStatus
import com.aspix2k.affected.TaskGroup
import com.aspix2k.affected.VerificationStatus
import com.aspix2k.affected.runClaimedGroupsWithPresentation
import com.intellij.execution.process.ProcessHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class CommandRunnerPresentationTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testClaimedCliBatchAttachesToTheAggregateWithoutPublishingAChildSession() = runBlocking {
        val sessions = AffectedRunSessions.getInstance(project)
        val claim = checkNotNull(sessions.claim(::claim))
        val view = RecordingView()
        val presentation = AffectedRunPresentation(claim, view)
        val root = checkNotNull(project.basePath)
        java.nio.file.Files.createDirectories(java.nio.file.Path.of(root))
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

    private fun java(): String = java.io.File(
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
