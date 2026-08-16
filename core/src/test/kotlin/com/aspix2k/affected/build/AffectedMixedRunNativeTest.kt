package com.aspix2k.affected.build

import com.aspix2k.affected.AffectedRunClaim
import com.aspix2k.affected.AffectedRunPresentation
import com.aspix2k.affected.AffectedRunSessions
import com.aspix2k.affected.AffectedStateSnapshot
import com.aspix2k.affected.AnalysisStatus
import com.aspix2k.affected.TaskGroup
import com.aspix2k.affected.VerificationStatus
import com.aspix2k.affected.runClaimedGroupsWithPresentation
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.plugins.gradle.settings.DistributionType
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.io.File
import java.util.Collections

class AffectedMixedRunNativeTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testGradleAndXcodeShareOneAffectedRunSession() = runBlocking {
        if (!nativeEnabled()) return@runBlocking
        if (!xcodeAvailable()) return@runBlocking
        val fixture = prepare("mixed-gradle-xcode-success", "test")
        try {
            assertTrue(run(fixture))
            assertTrue(File(fixture.root, "affected-gradle-test.marker").isFile)
            assertTrue(File(fixture.iosRoot, "affected-xcode-test.marker").isFile)
            assertAggregate(fixture, expectedExitCode = 0)
        } finally {
            cleanup(fixture)
        }
    }

    fun testChildFailureFailsTheSharedAffectedRunAfterTheOtherChildFinishes() = runBlocking {
        if (!nativeEnabled()) return@runBlocking
        if (!xcodeAvailable()) return@runBlocking
        val fixture = prepare("mixed-gradle-xcode-failure", "failingTest")
        try {
            assertFalse(run(fixture))
            assertTrue(File(fixture.root, "affected-gradle-failure.marker").isFile)
            assertTrue(File(fixture.iosRoot, "affected-xcode-test.marker").isFile)
            assertAggregate(fixture, expectedExitCode = 1)
        } finally {
            cleanup(fixture)
        }
    }

    fun testStoppingTheSharedAffectedRunCancelsItsRunningChildAndWaitsForCleanup() = runBlocking {
        if (!nativeEnabled()) return@runBlocking
        if (!xcodeAvailable()) return@runBlocking
        val fixture = prepare("mixed-gradle-xcode-cancellation", "slowTest", slowXcode = true)
        try {
            val outcome = async { run(fixture) }
            awaitMarker(File(fixture.root, "affected-gradle-started.marker"), outcome)
            awaitMarker(File(fixture.iosRoot, "affected-xcode-started.marker"), outcome)

            checkNotNull(fixture.descriptor().processHandler).destroyProcess()

            assertFalse(withTimeout(TERMINATION_TIMEOUT_MILLIS) { outcome.await() })
            assertFalse(File(fixture.root, "affected-gradle-finished.marker").exists())
            assertFalse(File(fixture.iosRoot, "affected-xcode-finished.marker").exists())
            assertEquals(0, AffectedRunSessions.getInstance(project).activeCount())
            assertAggregate(fixture, expectedExitCode = 1)
        } finally {
            cleanup(fixture)
        }
    }

    private fun prepare(name: String, gradleTask: String, slowXcode: Boolean = false): PreparedRun {
        val repository = CliConformanceRepository.configured
        val root = File(checkNotNull(project.basePath), name)
        val iosRoot = File(root, "iosApp")
        root.deleteRecursively()
        assertTrue(repository.fixture("mixed-gradle-xcode").copyRecursively(root, overwrite = true))
        assertTrue(repository.fixture("xcode").copyRecursively(iosRoot, overwrite = true))
        if (slowXcode) installSlowXcodeTest(iosRoot)
        installWrapper(repository, root)
        linkGradleProject(root)
        val claim = checkNotNull(AffectedRunSessions.getInstance(project).claim(::claim))
        assertTrue(claim.markRunning())
        val descriptors = Collections.synchronizedList(mutableListOf<RunContentDescriptor>())
        val presentation = AffectedRunPresentation.open(project, claim) { descriptor ->
            descriptors += descriptor
        }
        return PreparedRun(
            root,
            iosRoot,
            claim,
            descriptors,
            presentation,
            listOf(
                TaskGroup("GRADLE", root.path, listOf(gradleTask)),
                TaskGroup("XCODE", iosRoot.path, listOf(".:test")),
            ),
        )
    }

    private suspend fun run(fixture: PreparedRun): Boolean = withTimeout(SESSION_TIMEOUT_MILLIS) {
        runClaimedGroupsWithPresentation(
            fixture.claim,
            fixture.groups,
            Dispatchers.Default,
            stopAfterFirstFailure = false,
            presentation = fixture.presentation,
        ) { group ->
            group.runInPlannedExecutionRoot(project) {
                when (group.systemId) {
                    "GRADLE" -> GradleBuildSystem().runAndWaitSuspending(project, group.root, group.tasks)
                    "XCODE" -> XcodeBuildSystem().runAndWaitSuspending(project, group.root, group.tasks)
                    else -> false
                }
            }
        }
    }

    private suspend fun awaitMarker(marker: File, outcome: Deferred<Boolean>) {
        withTimeout(START_TIMEOUT_MILLIS) {
            while (!marker.isFile) {
                check(!outcome.isCompleted) { "Affected run finished before creating ${marker.path}" }
                delay(POLL_MILLIS)
            }
        }
    }

    private fun assertAggregate(fixture: PreparedRun, expectedExitCode: Int) {
        val descriptor = fixture.descriptor()
        val ui = checkNotNull(descriptor.runnerLayoutUi)
        val handler = checkNotNull(descriptor.processHandler)
        assertEquals("Affected", descriptor.displayName)
        assertEquals(
            listOf("Gradle · ${fixture.root.name}", "Xcode · ${fixture.root.name}/iosApp"),
            ui.contents.map { it.displayName }.sorted(),
        )
        assertEquals(2, ui.contents.map { it.component }.distinct().size)
        assertTrue(handler.isProcessTerminated)
        assertEquals(expectedExitCode, handler.exitCode)
    }

    private fun cleanup(fixture: PreparedRun) {
        File(fixture.root, "release-gradle.marker").writeText("release\n")
        File(fixture.iosRoot, "release-xcode.marker").writeText("release\n")
        AffectedRunSessions.getInstance(project).stopOwned()
        fixture.presentation.dispose()
        assertTrue(!fixture.root.exists() || fixture.root.deleteRecursively())
        assertFalse(fixture.root.exists())
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

    private fun installSlowXcodeTest(iosRoot: File) {
        File(iosRoot, "Tests/AppTests.swift").writeText(
            """
            import Foundation
            import XCTest

            final class AppTests: XCTestCase {
                func testAffectedExecution() throws {
                    let root = URL(fileURLWithPath: #filePath)
                        .deletingLastPathComponent().deletingLastPathComponent()
                    try "started".write(
                        to: root.appendingPathComponent("affected-xcode-started.marker"),
                        atomically: true,
                        encoding: .utf8
                    )
                    while !FileManager.default.fileExists(
                        atPath: root.appendingPathComponent("release-xcode.marker").path
                    ) {
                        Thread.sleep(forTimeInterval: 0.1)
                    }
                    try "finished".write(
                        to: root.appendingPathComponent("affected-xcode-finished.marker"),
                        atomically: true,
                        encoding: .utf8
                    )
                }
            }
            """.trimIndent(),
        )
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

    private fun nativeEnabled(): Boolean = System.getProperty("affected.cliConformance") == "true"

    private fun xcodeAvailable(): Boolean =
        System.getProperty("os.name").startsWith("Mac") && File("/usr/bin/xcodebuild").canExecute()

    private data class PreparedRun(
        val root: File,
        val iosRoot: File,
        val claim: AffectedRunClaim,
        val descriptors: MutableList<RunContentDescriptor>,
        val presentation: AffectedRunPresentation,
        val groups: List<TaskGroup>,
    ) {
        fun descriptor(): RunContentDescriptor = synchronized(descriptors) {
            assertEquals(1, descriptors.size)
            descriptors.single()
        }
    }

    private companion object {
        const val POLL_MILLIS = 50L
        const val START_TIMEOUT_MILLIS = 60_000L
        const val TERMINATION_TIMEOUT_MILLIS = 60_000L
        const val SESSION_TIMEOUT_MILLIS = 300_000L
    }
}
