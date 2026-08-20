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

class CliMixedCmakeMesonConformanceTest : BasePlatformTestCase() {

    private var registeredPoint = false
    private var previousStopAfterFirstFailure = false
    private val temporaryRoots = mutableListOf<File>()

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
        deleteCopiedRoots()
    }

    override fun tearDown() {
        try {
            temporaryRoots.toList().forEach { root ->
                if (root.exists()) {
                    check(root.deleteRecursively())
                }
            }
            temporaryRoots.clear()
            deleteCopiedRoots()
            AffectedSettings.getInstance().stopAfterFirstFailure = previousStopAfterFirstFailure
            super.tearDown()
        } finally {
            if (registeredPoint) {
                ApplicationManager.getApplication().extensionArea.unregisterExtensionPoint(BUILD_SYSTEM_POINT.name)
            }
        }
    }

    override fun runInDispatchThread(): Boolean = false

    fun testCmakeChangeDoesNotOwnTheSiblingMesonProject() = runBlocking {
        val root = mixedRepo()
        val owners = ModuleGraph.create(projectAt(root)).nodesFor(File(root, CMAKE_SOURCE))
        assertEquals(listOf("CMAKE"), owners.map { it.system.id }.distinct())
        assertTrue(owners.none { it.system.id == "MESON" })
    }

    fun testMesonChangeDoesNotOwnTheSiblingCmakeProject() = runBlocking {
        val root = mixedRepo()
        val owners = ModuleGraph.create(projectAt(root)).nodesFor(File(root, MESON_SOURCE))
        assertEquals(listOf("MESON"), owners.map { it.system.id }.distinct())
        assertTrue(owners.none { it.system.id == "CMAKE" })
    }

    fun testCmakeChangePlansOnlyTheCmakeGroup() = runBlocking {
        val root = mixedRepo()
        val prepared = prepared(root, CMAKE_SOURCE)
        assertEquals(listOf("CMAKE"), prepared.plan.groups.map { it.systemId }.distinct())
        assertTrue(prepared.plan.groups.single().tasks.any { it.contains("mixed_cmake_test") && it.endsWith(":test") })
        assertEquals(File(root, "cmake").canonicalPath, File(prepared.plan.groups.single().root).canonicalPath)
    }

    fun testMesonChangePlansOnlyTheMesonGroup() = runBlocking {
        val root = mixedRepo()
        val prepared = prepared(root, MESON_SOURCE)
        assertEquals(listOf("MESON"), prepared.plan.groups.map { it.systemId }.distinct())
        assertEquals(listOf(".:test"), prepared.plan.groups.single().tasks)
        assertEquals(File(root, "meson").canonicalPath, File(prepared.plan.groups.single().root).canonicalPath)
    }

    fun testProductionRegistrySeesBothAdaptersAndPlansBothSides() = runBlocking {
        val root = mixedRepo()
        val target = projectAt(root)
        val prepared = prepared(target, root, CMAKE_SOURCE, MESON_SOURCE)
        assertEquals(setOf("CMAKE", "MESON"), BuildSystems.of(target).map { it.id }.toSet())
        assertEquals(setOf("CMAKE", "MESON"), prepared.plan.groups.map { it.systemId }.toSet())
        assertEquals(2, prepared.plan.groups.size)
        assertTrue(prepared.plan.groups.single { it.systemId == "CMAKE" }.tasks.any { it.endsWith(":test") })
        assertEquals(listOf(".:test"), prepared.plan.groups.single { it.systemId == "MESON" }.tasks)
    }

    fun testSimultaneousChangesRunBothGroupsInOneVerificationSession() {
        if (!nativeEnabled()) return
        val root = mixedRepo()
        configureCmake(root)
        val outcome = runBlocking { runPrepared(root, CMAKE_SOURCE, MESON_SOURCE) }
        assertTrue(outcome.passed)
        assertEquals(setOf("CMAKE", "MESON"), outcome.plan.groups.map { it.systemId }.toSet())
        assertTrue("CMake marker was not written", markerExists(root, CMAKE_MARKER))
        assertTrue("Meson marker was not written", markerExists(root, MESON_MARKER))
    }

    fun testOneFailingGroupPreservesAggregateFailureAfterBothGroupsRan() {
        if (!nativeEnabled()) return
        val root = mixedRepo()
        File(root, MESON_SOURCE).writeText(
            File(root, MESON_SOURCE).readText().replace(
                "return 0;",
                "return 1;",
            ),
        )
        configureCmake(root)
        val outcome = runBlocking { runPrepared(root, CMAKE_SOURCE, MESON_SOURCE) }
        assertFalse(outcome.passed)
        assertTrue("CMake group did not finish after the Meson failure", markerExists(root, CMAKE_MARKER))
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
        prepared(projectAt(root), root, *paths)

    private suspend fun runPrepared(root: File, vararg paths: String): Verification.Outcome {
        val target = projectAt(root)
        val editors = currentEditors()
        return try {
            withTimeout(SESSION_TIMEOUT_MILLIS) {
                Verification.runAndWait(target, prepared(target, root, *paths))
            }
        } finally {
            disposeRunContents(editors)
        }
    }

    private fun mixedRepo(): File {
        val source = CliConformanceRepository.configured.fixture("mixed-cmake-meson")
        val root = createTempDirectory("affected-mixed-cmake-meson").toFile()
        temporaryRoots += root
        source.listFiles().orEmpty().forEach { child ->
            check(child.copyRecursively(File(root, child.name), overwrite = true))
        }
        return root
    }

    private fun configureCmake(root: File) {
        execute(File(root, "cmake"), listOf("cmake", "-S", ".", "-B", "build"))
    }

    private fun execute(directory: File, arguments: List<String>) {
        val output = File.createTempFile("affected-mixed-cmake-meson", ".log")
        try {
            val process = ProcessBuilder(arguments)
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .start()
            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            val text = output.readText()
            assertTrue("Timed out: ${arguments.joinToString(" ")}\n$text", completed)
            assertEquals("Failed: ${arguments.joinToString(" ")}\n$text", 0, process.exitValue())
        } finally {
            output.delete()
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
        val descriptor = CliConformanceRepository.configured.repositoryFile("src/main/resources/META-INF/plugin.xml")
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
        const val CMAKE_SOURCE = "cmake/value_test.c"
        const val MESON_SOURCE = "meson/value_test.c"
        const val CMAKE_MARKER = "mixed-cmake.marker"
        const val MESON_MARKER = "mixed-meson.marker"
        const val SESSION_TIMEOUT_MILLIS = 300_000L
        const val COMMAND_TIMEOUT_SECONDS = 180L
        const val MAX_MARKER_DEPTH = 12
        val COPIED_ROOTS = listOf("cmake", "meson")
    }
}
