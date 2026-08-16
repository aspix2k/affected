package com.aspix2k.affected.build

import com.aspix2k.affected.AffectedRunClaim
import com.aspix2k.affected.AffectedRunPresentation
import com.aspix2k.affected.AffectedRunSessions
import com.aspix2k.affected.AffectedStateSnapshot
import com.aspix2k.affected.AnalysisStatus
import com.aspix2k.affected.TaskGroup
import com.aspix2k.affected.VerificationStatus
import com.aspix2k.affected.runClaimedGroupsWithPresentation
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.plugins.gradle.settings.DistributionType
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.io.File
import java.util.Collections

class CliGradleSelectionDiagnosticsConformanceTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testStaticFallbackReasonsPrecedeCompositeTasksWithTheConfigurationCache() = runBlocking {
        if (!nativeEnabled()) return@runBlocking
        val repository = CliConformanceRepository.configured
        val root = File(checkNotNull(project.basePath), "gradle-selection-diagnostics")
        val markers = File(root, "markers")
        try {
            root.deleteRecursively()
            assertTrue(repository.fixture("gradle-kmp-fallback").copyRecursively(root, overwrite = true))
            installWrapper(repository, root)
            File(root, "gradle.properties").writeText(
                "org.gradle.configuration-cache=true\n" +
                    "systemProp.affected.kmp.markers=${markers.absolutePath}\n",
            )
            linkGradleProject(root)
            val changed = File(root, "shared/src/commonTest/kotlin/CommonTest.kt")
            val changes = BuildChanges(listOf(changed.path), setOf(changed.path), comparedToBase = true)

            val first = runAffectedGradle(root, changes)
            val cached = runAffectedGradle(root, changes)

            assertDiagnostics(first.output)
            assertDiagnostics(cached.output)
            assertTrue(cached.output, cached.output.contains("Reusing configuration cache"))
            assertEquals("android\n", File(markers, "android.marker").readText())
            assertEquals("ios\n", File(markers, "ios.marker").readText())
            assertEquals("custom\n", File(markers, "custom.marker").readText())
            assertEquals("included\n", File(markers, "included.marker").readText())
        } finally {
            AffectedRunSessions.getInstance(project).stopOwned()
            assertTrue(!root.exists() || root.deleteRecursively())
        }
    }

    private suspend fun runAffectedGradle(root: File, changes: BuildChanges): CapturedRun {
        val claim = claim()
        assertTrue(claim.markRunning())
        val descriptors = Collections.synchronizedList(mutableListOf<RunContentDescriptor>())
        val presentation = AffectedRunPresentation.open(project, claim) { descriptors += it }
        val output = StringBuffer()
        val connection = project.messageBus.connect()
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarting(
                executorId: String,
                environment: ExecutionEnvironment,
                handler: ProcessHandler,
            ) {
                handler.addProcessListener(object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                        output.append(event.text)
                    }
                })
            }
        })
        try {
            val group = TaskGroup("GRADLE", root.path, TASKS)
            val passed = withTimeout(PROCESS_TIMEOUT_MILLIS) {
                runClaimedGroupsWithPresentation(
                    claim,
                    listOf(group),
                    Dispatchers.Default,
                    stopAfterFirstFailure = false,
                    presentation = presentation,
                ) {
                    it.runInPlannedExecutionRoot(project) {
                        GradleBuildSystem().runAndWaitSuspending(project, it.root, it.tasks, changes)
                    }
                }
            }
            assertTrue(passed)
            val descriptor = synchronized(descriptors) {
                assertEquals(1, descriptors.size)
                descriptors.single()
            }
            assertTrue(checkNotNull(descriptor.processHandler).isProcessTerminated)
            return CapturedRun(output.toString())
        } finally {
            connection.dispose()
            presentation.dispose()
        }
    }

    private fun assertDiagnostics(output: String) {
        val diagnostics = listOf(
            "[Affected] Gradle selection - COMMON_SOURCE_SET_FAN_OUT: " +
                "common source-set change retains all target test tasks",
            "[Affected] Gradle selection - TASK_FAMILY_UNPROVEN: " +
                "task family is unproven; full target selection retained",
            "[Affected] Gradle selection - KOTLIN_NATIVE_EXACT_UNSUPPORTED: " +
                "Kotlin/Native exact selection is unsupported; full target task retained",
        )
        diagnostics.forEach { diagnostic -> assertEquals(output, 1, occurrences(output, diagnostic)) }
        val firstDiagnostic = output.indexOf(diagnostics.first())
        assertTrue(output, firstDiagnostic >= 0)
        TASKS.forEach { task ->
            val taskOutput = output.indexOf("> Task $task")
            assertTrue(output, taskOutput >= 0)
            assertTrue(output, firstDiagnostic < taskOutput)
        }
        assertFalse(output, output.contains("affectedSelectionDiagnosticsBeforeRun"))
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

    private fun installWrapper(repository: CliConformanceRepository, root: File) {
        repository.repositoryFile("gradlew").copyTo(File(root, "gradlew"), overwrite = true).setExecutable(true)
        val wrapper = File(root, "gradle/wrapper").apply { mkdirs() }
        repository.repositoryFile("gradle/wrapper/gradle-wrapper.jar")
            .copyTo(File(wrapper, "gradle-wrapper.jar"), overwrite = true)
        repository.repositoryFile("gradle/wrapper/gradle-wrapper.properties")
            .copyTo(File(wrapper, "gradle-wrapper.properties"), overwrite = true)
    }

    private fun linkGradleProject(root: File) {
        GradleSettings.getInstance(project).linkProject(
            GradleProjectSettings().apply {
                externalProjectPath = root.path
                distributionType = DistributionType.DEFAULT_WRAPPED
                gradleJvm = ExternalSystemJdkUtil.USE_INTERNAL_JAVA
            },
        )
    }

    private fun occurrences(value: String, needle: String): Int {
        var count = 0
        var offset = 0
        while (true) {
            offset = value.indexOf(needle, offset)
            if (offset < 0) return count
            count++
            offset += needle.length
        }
    }

    private fun nativeEnabled(): Boolean = System.getProperty("affected.cliConformance") == "true"

    private data class CapturedRun(val output: String)

    private companion object {
        val TASKS = listOf(
            ":shared:testDebugUnitTest",
            ":shared:iosSimulatorArm64Test",
            ":shared:customTest",
            ":included:includedTest",
        )
        const val PROCESS_TIMEOUT_MILLIS = 300_000L
    }
}
