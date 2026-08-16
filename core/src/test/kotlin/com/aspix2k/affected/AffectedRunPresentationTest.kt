package com.aspix2k.affected

import com.intellij.execution.process.ProcessHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AffectedRunPresentationTest {

    @Test
    fun `one parent presentation owns every child`() {
        val view = RecordingView()
        val claim = claim()
        val presentation = AffectedRunPresentation(claim, view)
        val gradle = RecordingChild()
        val xcode = RecordingChild()

        assertTrue(presentation.attach("Gradle · app", gradle))
        assertTrue(presentation.attach("Xcode · iosApp", xcode))

        assertEquals(1, view.publications)
        assertEquals(listOf("Gradle · app", "Xcode · iosApp"), view.labels)
        assertFalse(gradle.stopped)
        assertFalse(xcode.stopped)
    }

    @Test
    fun `stop waits for completion and rejects a late child`() {
        val view = RecordingView()
        val claim = claim()
        val presentation = AffectedRunPresentation(claim, view)
        val running = RecordingChild()
        val late = RecordingChild()
        assertTrue(presentation.attach("Gradle · app", running))

        view.handler.destroyProcess()

        assertTrue(claim.isCancellationRequested())
        assertTrue(view.handler.isProcessTerminating)
        assertFalse(view.handler.isProcessTerminated)
        assertTrue(running.stopped)
        assertFalse(presentation.attach("Xcode · iosApp", late))
        assertTrue(late.stopped)
        assertTrue(late.disposed)

        assertFalse(claim.complete(passed = true))
        assertTrue(view.handler.isProcessTerminated)
        assertEquals(1, view.handler.exitCode)
    }

    @Test
    fun `claim releases only after the parent terminal event`() {
        val view = RecordingView()
        var terminalAtRelease = false
        val claim = claim {
            terminalAtRelease = view.handler.isProcessTerminated && view.handler.exitCode == 0
        }
        AffectedRunPresentation(claim, view)

        assertTrue(claim.complete(passed = true))

        assertTrue(terminalAtRelease)
    }

    @Test
    fun `completed child output remains until the parent is disposed`() {
        val view = RecordingView()
        val claim = claim()
        val presentation = AffectedRunPresentation(claim, view)
        val child = RecordingChild()
        assertTrue(presentation.attach("Gradle · app", child))

        assertTrue(claim.complete(passed = true))

        assertFalse(child.disposed)
        presentation.dispose()
        assertTrue(child.disposed)
    }

    @Test
    fun `closing a running parent retains child ownership until cancellation completes`() {
        val view = RecordingView()
        val claim = claim()
        val presentation = AffectedRunPresentation(claim, view)
        val child = RecordingChild()
        assertTrue(presentation.attach("Gradle · app", child))

        presentation.dispose()

        assertTrue(child.stopped)
        assertFalse(child.disposed)
        assertFalse(claim.complete(passed = true))
        assertTrue(child.disposed)
    }

    @Test
    fun `one presentation follows every claimed group across dispatchers`() = runBlocking {
        val view = RecordingView()
        val claim = claim()
        val presentation = AffectedRunPresentation(claim, view)
        val groups = listOf(
            TaskGroup("GRADLE", "/repo/app", listOf(":test")),
            TaskGroup("XCODE", "/repo/iosApp", listOf(".:validate")),
        )

        val passed = runClaimedGroupsWithPresentation(
            claim,
            groups,
            Dispatchers.Default,
            stopAfterFirstFailure = false,
            presentation = presentation,
        ) { group ->
            val child = RecordingChild()
            checkNotNull(currentAffectedRunPresentation()).attach(group.systemId, child)
        }

        assertTrue(passed)
        assertEquals(listOf("GRADLE", "XCODE"), view.labels.sorted())
        assertTrue(view.handler.isProcessTerminated)
        assertEquals(0, view.handler.exitCode)
    }

    @Test
    fun `failed parent publication releases its partial view without binding the claim`() {
        var released = false
        val claim = claim { released = true }
        val view = FailingView()

        assertFailsWith<IllegalStateException> { AffectedRunPresentation(claim, view) }

        assertTrue(view.disposed)
        assertTrue(claim.complete(passed = true))
        assertTrue(released)
    }

    private fun claim(release: () -> Unit = {}) = AffectedRunClaim(
        snapshot = AffectedStateSnapshot(
            revision = 1,
            analysisStatus = AnalysisStatus.READY,
            modules = emptyList(),
            verificationStatus = VerificationStatus.PREPARING,
        ),
        changes = null,
        prepared = null,
        markRunning = { true },
        release = release,
    )

    private class RecordingView : AffectedRunView {
        var publications = 0
        val labels = mutableListOf<String>()
        lateinit var handler: ProcessHandler

        override fun publish(handler: ProcessHandler) {
            publications++
            this.handler = handler
            handler.startNotify()
        }

        override fun attach(label: String, child: AffectedRunChild) {
            labels += label
        }

        override fun dispose() = Unit
    }

    private class RecordingChild : AffectedRunChild {
        override val component = javax.swing.JPanel()
        override val preferredFocus = component
        var stopped = false
        var disposed = false

        override fun stop() {
            stopped = true
        }

        override fun dispose() {
            disposed = true
        }
    }

    private class FailingView : AffectedRunView {
        var disposed = false

        override fun publish(handler: ProcessHandler): Unit = error("publication failed")

        override fun attach(label: String, child: AffectedRunChild) = Unit

        override fun dispose() {
            disposed = true
        }
    }
}
