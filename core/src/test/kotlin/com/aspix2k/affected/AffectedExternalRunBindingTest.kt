package com.aspix2k.affected

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.OutputStream
import javax.swing.JPanel

class AffectedExternalRunBindingTest : BasePlatformTestCase() {

    fun testOwnedExecutionBecomesHeadlessAndAttachesItsStructuredDescriptor() {
        val view = RecordingView()
        val presentation = AffectedRunPresentation(claim(), view)
        val owned = ExecutionEnvironment()
        val unrelated = ExecutionEnvironment()
        val binding = checkNotNull(
            AffectedExternalRunBinding.open(project, presentation, "Gradle · app") { environment, _ ->
                environment === owned
            },
        )

        binding.processStartScheduled("Run", unrelated)
        assertFalse(unrelated.isHeadless)

        binding.processStartScheduled("Run", owned)
        assertTrue(owned.isHeadless)
        val handler = RecordingHandler()
        owned.contentToReuse = RunContentDescriptor(null, handler, JPanel(), "Gradle")
        binding.processStarting("Run", owned, handler)

        assertEquals(listOf("Gradle · app"), view.labels)
        assertFalse(handler.destroyed)
    }

    fun testMissingHeadlessCompatibilityRefusesTheLaunchBinding() {
        val presentation = AffectedRunPresentation(claim(), RecordingView())

        assertNull(
            AffectedExternalRunBinding.open(
                project,
                presentation,
                "Maven · app",
                enableHeadless = null,
            ) { _, _ -> true },
        )
    }

    fun testMavenBindingMatchesOnlyItsExactProgramRunnerCallback() {
        val view = RecordingView()
        val presentation = AffectedRunPresentation(claim(), view)
        val callback = object : ProgramRunner.Callback {
            override fun processStarted(descriptor: RunContentDescriptor) = Unit
        }
        val owned = ExecutionEnvironment().apply { this.callback = callback }
        val unrelated = ExecutionEnvironment()
        val binding = checkNotNull(
            AffectedExternalRunBinding.open(project, presentation, "Maven · app") { environment, _ ->
                environment.callback === callback
            },
        )

        binding.processStartScheduled("Run", unrelated)
        binding.processStartScheduled("Run", owned)
        val handler = RecordingHandler()
        owned.contentToReuse = RunContentDescriptor(null, handler, JPanel(), "Maven")
        binding.processStarting("Run", owned, handler)

        assertFalse(unrelated.isHeadless)
        assertTrue(owned.isHeadless)
        assertEquals(listOf("Maven · app"), view.labels)
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

    private class RecordingView : AffectedRunView {
        val labels = mutableListOf<String>()

        override fun publish(handler: ProcessHandler) = handler.startNotify()

        override fun attach(label: String, child: AffectedRunChild) {
            labels += label
        }

        override fun dispose() = Unit
    }

    private class RecordingHandler : ProcessHandler() {
        var destroyed = false

        override fun destroyProcessImpl() {
            destroyed = true
            notifyProcessTerminated(1)
        }

        override fun detachProcessImpl() = notifyProcessDetached()

        override fun detachIsDefault(): Boolean = false

        override fun getProcessInput(): OutputStream? = null
    }
}
