package com.aspix2k.affected.build

import com.aspix2k.affected.AffectedSettings
import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.ProjectChanges
import com.aspix2k.affected.Verification
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.w3c.dom.Element
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory

class CliMixedPolyglotConformanceTest : BasePlatformTestCase() {

    private var registeredPoint = false
    private var previousStopAfterFirstFailure = false

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
        ExtensionTestUtil.maskExtensions(BUILD_SYSTEM_POINT, descriptorBuildSystems(), testRootDisposable)
        previousStopAfterFirstFailure = AffectedSettings.getInstance().stopAfterFirstFailure
        AffectedSettings.getInstance().stopAfterFirstFailure = false
    }

    override fun tearDown() {
        try {
            AffectedSettings.getInstance().stopAfterFirstFailure = previousStopAfterFirstFailure
            super.tearDown()
        } finally {
            if (registeredPoint) {
                ApplicationManager.getApplication().extensionArea.unregisterExtensionPoint(BUILD_SYSTEM_POINT.name)
            }
        }
    }

    override fun runInDispatchThread(): Boolean = false

    fun testCmakeChangeDoesNotPlanTheSiblingDotnetProject() = runBlocking {
        val root = mixedRepo()
        val prepared = prepared(root, "native/alpha_test.c")

        assertEquals(listOf("CMAKE"), prepared.plan.groups.map { it.systemId }.distinct())
        assertTrue(prepared.plan.groups.single().tasks.any { it.contains("mixed_alpha") })
    }

    fun testDotnetChangeDoesNotPlanTheSiblingCmakeTargets() = runBlocking {
        val root = mixedRepo()
        val prepared = prepared(root, "Lib.Tests/ValueTest.cs")

        assertEquals(listOf("DOTNET"), prepared.plan.groups.map { it.systemId }.distinct())
        assertTrue(prepared.plan.groups.single().tasks.any { it.contains("Lib.Tests") && it.endsWith(":test") })
        assertTrue(prepared.plan.groups.single().tasks.none { "mixed_alpha" in it })
    }

    fun testProductionRegistryPlansBothAdaptersInOneRepository() = runBlocking {
        val root = mixedRepo()
        val systems = BuildSystems.of(project).map { it.id }.toSet()
        val prepared = prepared(root, "native/alpha_test.c", "Lib.Tests/ValueTest.cs")

        assertEquals(setOf("CMAKE", "DOTNET"), systems)
        assertEquals(setOf("CMAKE", "DOTNET"), prepared.plan.groups.map { it.systemId }.toSet())
        assertEquals(2, prepared.plan.groups.size)
    }

    fun testSimultaneousChangesRunThroughOnePreparedVerificationSession() {
        if (!nativeEnabled()) return
        val root = mixedRepo()
        configureCmake(root)

        val outcome = runBlocking { runPrepared(root, "native/alpha_test.c", "Lib.Tests/ValueTest.cs") }

        assertTrue(outcome.passed)
        assertEquals(setOf("CMAKE", "DOTNET"), outcome.plan.groups.map { it.systemId }.toSet())
        assertTrue("CMake marker was not written", markerExists(root, CMAKE_MARKER))
        assertTrue(".NET marker was not written", markerExists(root, DOTNET_MARKER))
    }

    fun testOneFailingGroupPreservesAggregateFailureAfterBothGroupsRan() {
        if (!nativeEnabled()) return
        val root = mixedRepo()
        File(root, "native/alpha_test.c").appendText("\n#error requested mixed fixture failure\n")
        configureCmake(root)

        val outcome = runBlocking { runPrepared(root, "native/alpha_test.c", "Lib.Tests/ValueTest.cs") }

        assertFalse(outcome.passed)
        assertTrue(".NET group did not finish after the CMake failure", markerExists(root, DOTNET_MARKER))
    }

    fun testSpaceUnicodeAndLeadingDashRootRunsBothGroups() {
        if (!nativeEnabled()) return
        val parent = createTempDirectory("affected mixed ü ").toFile()
        val root = File(parent, "-repo данные")
        try {
            check(root.mkdirs())
            copyFixtureTo(root)
            configureCmake(root)
            val targetProject = projectAt(root)

            val outcome = runBlocking {
                runPrepared(targetProject, root, "native/alpha_test.c", "Lib.Tests/ValueTest.cs")
            }

            assertTrue(outcome.passed)
            assertTrue("CMake marker was not written", markerExists(root, CMAKE_MARKER))
            assertTrue(".NET marker was not written", markerExists(root, DOTNET_MARKER))
        } finally {
            check(parent.deleteRecursively())
        }
    }

    private suspend fun prepared(
        targetProject: Project,
        root: File,
        vararg paths: String,
    ): Verification.Prepared {
        val files = paths.map { File(root, it) }
        val changes = ProjectChanges.Result(
            files = files,
            apiTouched = emptySet(),
            exactSelectionEligible = files.toSet(),
            comparedToBase = true,
        )
        return Verification.prepare(ModuleGraph.create(targetProject), changes).testsOnly
    }

    private suspend fun prepared(root: File, vararg paths: String): Verification.Prepared =
        prepared(project, root, *paths)

    private suspend fun runPrepared(
        targetProject: Project,
        root: File,
        vararg paths: String,
    ): Verification.Outcome {
        val editors = currentEditors()
        return try {
            withTimeout(SESSION_TIMEOUT_MILLIS) {
                Verification.runAndWait(targetProject, prepared(targetProject, root, *paths))
            }
        } finally {
            disposeRunContents(editors)
        }
    }

    private suspend fun runPrepared(root: File, vararg paths: String): Verification.Outcome =
        runPrepared(project, root, *paths)

    private fun mixedRepo(): File {
        val source = CliConformanceRepository.configured.fixture("mixed-cmake-dotnet")
        val root = File(requireNotNull(project.basePath))
        GENERATED_PATHS.forEach { path -> check(File(root, path).deleteRecursively()) }
        copyFixtureTo(root, source)
        return root
    }

    private fun copyFixtureTo(
        root: File,
        source: File = CliConformanceRepository.configured.fixture("mixed-cmake-dotnet"),
    ) {
        source.listFiles().orEmpty().forEach { child ->
            check(child.copyRecursively(File(root, child.name), overwrite = true))
        }
    }

    private fun nativeEnabled(): Boolean = System.getProperty("affected.cliConformance") == "true"

    private fun configureCmake(root: File) {
        val output = File.createTempFile("affected-mixed-cmake", ".log")
        try {
            val process = ProcessBuilder("cmake", "-S", ".", "-B", "build")
                .directory(root)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .start()
            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            val text = output.readText()
            assertTrue("CMake configuration timed out\n$text", completed)
            assertEquals(text, 0, process.exitValue())
        } finally {
            output.delete()
        }
    }

    private fun markerExists(root: File, name: String): Boolean =
        Files.find(root.toPath(), MAX_MARKER_DEPTH, { path, attributes ->
            attributes.isRegularFile && path.fileName.toString() == name
        }).use { files -> files.findFirst().isPresent }

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

    private fun descriptorBuildSystems(): List<BuildSystem> {
        val descriptor = repositoryFile("src/main/resources/META-INF/plugin.xml")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val elements = factory.newDocumentBuilder().parse(descriptor).getElementsByTagName("buildSystem")
        return (0 until elements.length).map { index ->
            val implementation = (elements.item(index) as Element).getAttribute("implementation")
            Class.forName(implementation).getDeclaredConstructor().newInstance() as BuildSystem
        }
    }

    private fun repositoryFile(path: String): File = CliConformanceRepository.configured.repositoryFile(path)

    private fun projectAt(root: File): Project {
        val delegate = project
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, arguments ->
            if (method.name == "getBasePath") {
                root.invariantSeparatorsPath
            } else {
                try {
                    method.invoke(delegate, *(arguments ?: emptyArray()))
                } catch (error: InvocationTargetException) {
                    throw error.cause ?: error
                }
            }
        } as Project
    }

    private companion object {
        val BUILD_SYSTEM_POINT = ExtensionPointName.create<BuildSystem>("com.aspix2k.affected.buildSystem")
        const val CMAKE_MARKER = "mixed-cmake.marker"
        const val DOTNET_MARKER = "mixed-dotnet.marker"
        const val COMMAND_TIMEOUT_SECONDS = 180L
        const val SESSION_TIMEOUT_MILLIS = 300_000L
        const val MAX_MARKER_DEPTH = 12
        val GENERATED_PATHS = listOf("build", "Lib/bin", "Lib/obj", "Lib.Tests/bin", "Lib.Tests/obj")
    }
}
