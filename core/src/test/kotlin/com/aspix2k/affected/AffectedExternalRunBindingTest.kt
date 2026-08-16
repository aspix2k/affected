package com.aspix2k.affected

import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
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

    fun testOwnedExecutionPrintsInitialOutputBeforeProcessOutput() {
        val presentation = AffectedRunPresentation(claim(), RecordingView())
        val owned = ExecutionEnvironment()
        val binding = checkNotNull(
            AffectedExternalRunBinding.open(
                project,
                presentation,
                "Gradle · app",
                initialOutput = "[Affected] Gradle selection - COMMON_SOURCE_SET_FAN_OUT\n",
            ) { environment, _ -> environment === owned },
        )
        val output = mutableListOf<String>()
        val handler = RecordingHandler().apply {
            addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                    output += event.text
                }
            })
        }

        binding.processStartScheduled("Run", owned)
        owned.contentToReuse = RunContentDescriptor(null, handler, JPanel(), "Gradle")
        binding.processStarting("Run", owned, handler)

        assertTrue(output.isEmpty())
        handler.startNotify()
        handler.notifyTextAvailable("> Task :shared:testDebugUnitTest\n", ProcessOutputTypes.STDOUT)
        assertEquals(
            listOf(
                "[Affected] Gradle selection - COMMON_SOURCE_SET_FAN_OUT\n",
                "> Task :shared:testDebugUnitTest\n",
            ),
            output,
        )
    }

    fun testOwnedExecutionCannotReuseAnExistingDirectRunDescriptor() {
        val presentation = AffectedRunPresentation(claim(), RecordingView())
        val directHandler = RecordingHandler()
        val directDescriptor = RunContentDescriptor(null, directHandler, JPanel(), "Direct Gradle")
        val owned = ExecutionEnvironment().apply { contentToReuse = directDescriptor }
        val binding = checkNotNull(
            AffectedExternalRunBinding.open(project, presentation, "Gradle · app") { environment, _ ->
                environment === owned
            },
        )

        binding.processStartScheduled("Run", owned)

        assertTrue(owned.isHeadless)
        assertNull(owned.contentToReuse)
        assertFalse(directHandler.destroyed)
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

    fun testHeadlessFailureSkipsOwnedExecutionBeforeItCanPublishContent() {
        val presentation = AffectedRunPresentation(claim(), RecordingView())
        val owned = ExecutionEnvironment()
        val binding = checkNotNull(
            AffectedExternalRunBinding.open(
                project,
                presentation,
                "Gradle · app",
                enableHeadless = { false },
            ) { environment, _ -> environment === owned },
        )

        binding.processStartScheduled("Run", owned)

        assertEquals(true, owned.getUserData(ExecutionManager.EXECUTION_SKIP_RUN))
        assertFalse(owned.isHeadless)
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
