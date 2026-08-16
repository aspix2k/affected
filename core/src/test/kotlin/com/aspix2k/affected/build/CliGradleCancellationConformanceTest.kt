package com.aspix2k.affected.build

import com.aspix2k.affected.AffectedRunSessions
import com.aspix2k.affected.OwnedExternalTaskExecution
import com.aspix2k.affected.runOwnedExternalTask
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkUtil
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.externalSystem.util.task.TaskExecutionSpec
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.plugins.gradle.settings.DistributionType
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliGradleCancellationConformanceTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testOwnedGradleCancellationLeavesAnUnrelatedIdeTaskRunning() = runBlocking {
        if (!nativeEnabled()) return@runBlocking
        val source = fixtureRoot()
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        val target = File(checkNotNull(project.basePath), "gradle-cancellation")
        val existingEditors = currentEditors()
        val ownedRoot = File(target, "owned")
        val unrelatedRoot = File(target, "unrelated")
        var owned: Deferred<Boolean>? = null
        var unrelated: Deferred<Boolean>? = null
        try {
            target.deleteRecursively()
            assertTrue(source.copyRecursively(ownedRoot, overwrite = true))
            assertTrue(source.copyRecursively(unrelatedRoot, overwrite = true))
            installWrapper(ownedRoot)
            installWrapper(unrelatedRoot)
            linkGradleProject(ownedRoot)
            linkGradleProject(unrelatedRoot)

            owned = async(Dispatchers.Default) {
                GradleBuildSystem().runAndWaitSuspending(project, ownedRoot.path, listOf("waitForRelease"))
            }
            waitForMarker(File(ownedRoot, "markers/started.marker"), owned)
            unrelated = async(Dispatchers.Default) { runUnrelatedTask(unrelatedRoot) }
            waitForMarker(File(unrelatedRoot, "markers/started.marker"), unrelated)

            assertEquals(1, AffectedRunSessions.getInstance(project).stopOwned())
            assertFalse(withTimeout(PROCESS_TIMEOUT_MILLIS) { owned.await() })
            assertFalse(unrelated.isCompleted)
            assertFalse(File(ownedRoot, "markers/completed.marker").exists())

            File(unrelatedRoot, "markers/release.marker").writeText("release")
            assertTrue(withTimeout(PROCESS_TIMEOUT_MILLIS) { unrelated.await() })
            assertTrue(File(unrelatedRoot, "markers/completed.marker").isFile)
        } finally {
            File(ownedRoot, "markers/release.marker").runCatching { parentFile.mkdirs(); writeText("release") }
            File(unrelatedRoot, "markers/release.marker").runCatching { parentFile.mkdirs(); writeText("release") }
            AffectedRunSessions.getInstance(project).stopOwned()
            withTimeoutOrNull(PROCESS_TIMEOUT_MILLIS) { owned?.await() }
            withTimeoutOrNull(PROCESS_TIMEOUT_MILLIS) { unrelated?.await() }
            disposeRunContents(existingEditors)
            target.deleteRecursively()
        }
    }

    fun testOwnedGradleCancellationRetriesAfterTheEnvironmentIsPrepared() = runBlocking {
        if (!nativeEnabled()) return@runBlocking
        val source = fixtureRoot()
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        val target = File(checkNotNull(project.basePath), "gradle-cancellation-startup")
        val existingEditors = currentEditors()
        val root = File(target, "owned")
        val sessions = AffectedRunSessions.getInstance(project)
        val execution = OwnedExternalTaskExecution(cancelTask = ::cancelExternalTask)
        val stopped = AtomicBoolean()
        val ownedTask = AtomicReference<ExternalSystemTaskId?>()
        try {
            target.deleteRecursively()
            assertTrue(source.copyRecursively(root, overwrite = true))
            installWrapper(root)
            linkGradleProject(root)
            val settings = ExternalSystemTaskExecutionSettings().apply {
                externalProjectPath = root.path
                taskNames = listOf("waitForRelease")
                externalSystemIdString = GradleConstants.SYSTEM_ID.id
            }

            val passed = runOwnedExternalTask(sessions, execution) { listener ->
                val stoppingListener = object : ExternalSystemTaskNotificationListener by listener {
                    override fun onStart(projectPath: String, id: ExternalSystemTaskId) {
                        ownedTask.set(id)
                        listener.onStart(projectPath, id)
                        stopped.set(sessions.stopOwned() == 1)
                    }
                }
                ExternalSystemUtil.runTask(
                    gradleTaskExecutionSpec(project, settings, stoppingListener, execution.callback),
                )
            }

            assertFalse(passed)
            assertTrue(stopped.get())
            val taskId = checkNotNull(ownedTask.get())
            assertNull(ExternalSystemProcessingManager.getInstance().findTask(taskId))
            assertFalse(File(root, "markers/started.marker").exists())
            assertFalse(File(root, "markers/completed.marker").exists())
        } finally {
            File(root, "markers/release.marker").runCatching { parentFile.mkdirs(); writeText("release") }
            sessions.stopOwned()
            disposeRunContents(existingEditors)
            target.deleteRecursively()
        }
    }

    private suspend fun runUnrelatedTask(root: File): Boolean = withContext(Dispatchers.IO) {
        val passed = AtomicBoolean()
        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = root.path
            taskNames = listOf("waitForRelease")
            externalSystemIdString = GradleConstants.SYSTEM_ID.id
        }
        val callback = object : TaskCallback {
            override fun onSuccess() {
                passed.set(true)
            }

            override fun onFailure() {
                passed.set(false)
            }
        }
        ExternalSystemUtil.runTask(
            TaskExecutionSpec.create()
                .withProject(project)
                .withSystemId(GradleConstants.SYSTEM_ID)
                .withExecutorId(DefaultRunExecutor.EXECUTOR_ID)
                .withSettings(settings)
                .withListener(ExternalSystemTaskNotificationListener.NULL_OBJECT)
                .withCallback(callback)
                .withProgressExecutionMode(ProgressExecutionMode.NO_PROGRESS_SYNC)
                .build(),
        )
        passed.get()
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

    private fun installWrapper(root: File) {
        val repository = repositoryRoot()
        File(repository, "gradlew").copyTo(File(root, "gradlew"), overwrite = true).setExecutable(true)
        val wrapper = File(root, "gradle/wrapper").apply { mkdirs() }
        File(repository, "gradle/wrapper/gradle-wrapper.jar")
            .copyTo(File(wrapper, "gradle-wrapper.jar"), overwrite = true)
        File(repository, "gradle/wrapper/gradle-wrapper.properties")
            .copyTo(File(wrapper, "gradle-wrapper.properties"), overwrite = true)
    }

    private suspend fun waitForMarker(marker: File, task: Deferred<Boolean>) {
        withTimeout(START_TIMEOUT_MILLIS) {
            while (!marker.isFile) {
                check(!task.isCompleted) { "Gradle task finished before creating $marker: ${task.await()}" }
                delay(50)
            }
        }
    }

    private fun fixtureRoot(): File = File(repositoryRoot(), "conformance/cli-fixtures/gradle-cancellation")

    private fun nativeEnabled(): Boolean = System.getProperty(CONFORMANCE_PROPERTY) == "true"

    private fun currentEditors(): Set<Editor> {
        var editors = emptySet<Editor>()
        ApplicationManager.getApplication().invokeAndWait {
            editors = EditorFactory.getInstance().allEditors.toSet()
        }
        return editors
    }

    private fun disposeRunContents(existingEditors: Set<Editor>) {
        ApplicationManager.getApplication().invokeAndWait {
            RunContentManager.getInstanceIfCreated(project)?.let { manager ->
                val executor = DefaultRunExecutor.getRunExecutorInstance()
                manager.allDescriptors.toList().forEach { manager.removeRunContent(executor, it) }
            }
            val factory = EditorFactory.getInstance()
            factory.allEditors.filterNot(existingEditors::contains).forEach(factory::releaseEditor)
        }
    }

    private fun repositoryRoot(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "gradlew").isFile && File(it, "conformance/cli-fixtures").isDirectory }
        ?: error("Affected repository root is missing")

    private companion object {
        const val CONFORMANCE_PROPERTY = "affected.cliConformance"
        const val START_TIMEOUT_MILLIS = 120_000L
        const val PROCESS_TIMEOUT_MILLIS = 45_000L
    }
}
