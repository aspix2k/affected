package com.aspix2k.affected.build

import com.aspix2k.affected.AffectedSettings
import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.Plan
import com.aspix2k.affected.ProjectChanges
import com.aspix2k.affected.TaskGroup
import com.aspix2k.affected.Verification
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.idea.maven.execution.MavenRunner
import org.jetbrains.idea.maven.execution.MavenRunnerSettings
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.plugins.gradle.settings.DistributionType
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File
import java.util.concurrent.TimeUnit

class CliMixedGradleMavenConformanceTest : HeavyPlatformTestCase() {

    private var registeredPoint = false
    private var previousStopAfterFirstFailure = false
    private var previousMavenJre: String? = null

    override fun setUp() {
        super.setUp()
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
            listOf(GradleBuildSystem(), MavenBuildSystem()),
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
            unlinkGradleProjects()
            unlinkMavenProjects()
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

    fun testGradleChangeDoesNotOwnTheSiblingMavenProject() = runBlocking {
        val root = mixedRepo()
        val owners = ModuleGraph.create(project).nodesFor(File(root, GRADLE_SOURCE))
        assertEquals(listOf(GradleConstants.SYSTEM_ID.id), owners.map { it.system.id }.distinct())
        assertTrue(owners.none { it.system.id == "MAVEN" })
    }

    fun testMavenChangeDoesNotOwnTheSiblingGradleProject() = runBlocking {
        val root = mixedRepo()
        val owners = ModuleGraph.create(project).nodesFor(File(root, MAVEN_SOURCE))
        assertEquals(listOf("MAVEN"), owners.map { it.system.id }.distinct())
        assertTrue(owners.none { it.system.id == GradleConstants.SYSTEM_ID.id })
    }

    fun testImportedAdaptersArePresentAndMavenChangePlansTheMavenGroup() = runBlocking {
        val root = mixedRepo()
        val prepared = prepared(root, MAVEN_SOURCE)
        assertEquals(
            setOf(GradleConstants.SYSTEM_ID.id, "MAVEN"),
            BuildSystems.of(project).map { it.id }.toSet(),
        )
        assertEquals(listOf("MAVEN"), prepared.plan.groups.map { it.systemId }.distinct())
        assertEquals(File(root, "maven").canonicalPath, File(prepared.plan.groups.single().root).canonicalPath)
        assertEquals(listOf("affected-mixed-gradle-maven:test"), prepared.plan.groups.single().tasks)
    }

    fun testSimultaneousChangesRunBothGroupsInOneVerificationSession() {
        if (!nativeEnabled()) return
        val root = mixedRepo()
        val outcome = runBlocking { runBothGroups(root) }
        val gradleMarker = File(root, "gradle/mixed-gradle.marker")
        val mavenMarker = File(root, "maven/mixed-maven.marker")
        assertTrue("mixed Gradle+Maven verification failed", outcome.passed)
        assertEquals(
            setOf(GradleConstants.SYSTEM_ID.id, "MAVEN"),
            outcome.plan.groups.map { it.systemId }.toSet(),
        )
        assertTrue("Gradle marker was not written", gradleMarker.isFile)
        assertTrue("Maven marker was not written", mavenMarker.isFile)
    }

    fun testOneFailingGroupPreservesAggregateFailureAfterBothGroupsRan() {
        if (!nativeEnabled()) return
        val root = mixedRepo()
        File(root, "gradle/build.gradle").appendText(
            "\ntasks.named(\"test\") { doLast { throw new GradleException('requested mixed fixture failure') } }\n",
        )
        val outcome = runBlocking { runBothGroups(root) }
        assertFalse(outcome.passed)
        assertTrue(
            "Maven group did not finish after the Gradle failure",
            File(root, "maven/mixed-maven.marker").isFile,
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
                            TaskGroup(GradleConstants.SYSTEM_ID.id, File(root, "gradle").path, listOf("test")),
                            TaskGroup("MAVEN", File(root, "maven").path, listOf(":test")),
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
        unlinkGradleProjects()
        unlinkMavenProjects()
        deleteCopiedRoots()
        val source = CliConformanceRepository.configured.fixture("mixed-gradle-maven")
        val root = File(requireNotNull(project.basePath))
        source.listFiles().orEmpty().forEach { child ->
            check(child.copyRecursively(File(root, child.name), overwrite = true))
        }
        val gradleRoot = File(root, "gradle")
        installWrapper(gradleRoot)
        linkGradleProject(gradleRoot)
        importMaven(File(root, "maven/pom.xml"))
        return root
    }

    private fun installWrapper(root: File) {
        val repository = CliConformanceRepository.configured
        repository.repositoryFile("gradlew").copyTo(File(root, "gradlew"), overwrite = true).setExecutable(true)
        val wrapper = File(root, "gradle/wrapper").apply { mkdirs() }
        repository.repositoryFile("gradle/wrapper/gradle-wrapper.jar")
            .copyTo(File(wrapper, "gradle-wrapper.jar"), overwrite = true)
        repository.repositoryFile("gradle/wrapper/gradle-wrapper.properties")
            .copyTo(File(wrapper, "gradle-wrapper.properties"), overwrite = true)
    }

    private fun linkGradleProject(root: File) {
        val settings = GradleSettings.getInstance(project)
        val path = root.canonicalPath
        if (settings.linkedProjectsSettings.any { File(it.externalProjectPath).canonicalPath == path }) {
            return
        }
        settings.linkProject(
            GradleProjectSettings().apply {
                externalProjectPath = root.path
                distributionType = DistributionType.DEFAULT_WRAPPED
                gradleJvm = ExternalSystemJdkUtil.USE_INTERNAL_JAVA
            },
        )
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

    private fun unlinkGradleProjects() {
        val settings = GradleSettings.getInstance(project)
        settings.linkedProjectsSettings.toList().forEach { linked ->
            settings.unlinkExternalProject(linked.externalProjectPath)
        }
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
            factory.allEditors.filterNot(existingEditors::contains).forEach(factory::releaseEditor)
        }
    }

    private companion object {
        val BUILD_SYSTEM_POINT = ExtensionPointName.create<BuildSystem>("com.aspix2k.affected.buildSystem")
        const val GRADLE_SOURCE = "gradle/src/main/java/app/Value.java"
        const val MAVEN_SOURCE = "maven/src/main/java/lib/Value.java"
        const val SESSION_TIMEOUT_MILLIS = 300_000L
        const val IMPORT_TIMEOUT_SECONDS = 60L
        val COPIED_ROOTS = listOf("gradle", "maven")
    }
}
