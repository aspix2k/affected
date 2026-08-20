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
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory

class CliMixedRPythonConformanceTest : BasePlatformTestCase() {

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

    fun testRChangeDoesNotOwnTheSiblingPythonProject() = runBlocking {
        val root = mixedRepo()
        val owners = ModuleGraph.create(projectAt(root)).nodesFor(File(root, R_SOURCE))
        assertEquals(listOf("RPROJECT"), owners.map { it.system.id }.distinct())
        assertTrue(owners.none { it.system.id == "PYTHON" })
    }

    fun testPythonChangeDoesNotOwnTheSiblingRProject() = runBlocking {
        val root = mixedRepo()
        val owners = ModuleGraph.create(projectAt(root)).nodesFor(File(root, PYTHON_SOURCE))
        assertEquals(listOf("PYTHON"), owners.map { it.system.id }.distinct())
        assertTrue(owners.none { it.system.id == "RPROJECT" })
    }

    fun testRChangePlansOnlyTheRGroup() = runBlocking {
        val root = mixedRepo()
        val prepared = prepared(root, R_SOURCE)
        assertEquals(listOf("RPROJECT"), prepared.plan.groups.map { it.systemId }.distinct())
        assertEquals(listOf(".:test"), prepared.plan.groups.single().tasks)
        assertEquals(File(root, "stats").canonicalPath, File(prepared.plan.groups.single().root).canonicalPath)
    }

    fun testPythonChangePlansOnlyThePythonGroup() = runBlocking {
        val root = mixedRepo()
        val prepared = prepared(root, PYTHON_SOURCE)
        assertEquals(listOf("PYTHON"), prepared.plan.groups.map { it.systemId }.distinct())
        assertEquals(listOf(".:test"), prepared.plan.groups.single().tasks)
        assertEquals(File(root, "analysis").canonicalPath, File(prepared.plan.groups.single().root).canonicalPath)
    }

    fun testProductionRegistrySeesBothAdaptersAndPlansBothSides() = runBlocking {
        val root = mixedRepo()
        val target = projectAt(root)
        val prepared = prepared(target, root, R_SOURCE, PYTHON_SOURCE)
        assertEquals(setOf("RPROJECT", "PYTHON"), BuildSystems.of(target).map { it.id }.toSet())
        assertEquals(setOf("RPROJECT", "PYTHON"), prepared.plan.groups.map { it.systemId }.toSet())
        assertEquals(2, prepared.plan.groups.size)
        assertTrue(prepared.plan.groups.all { it.tasks == listOf(".:test") })
    }

    fun testSimultaneousChangesRunBothGroupsInOneVerificationSession() {
        if (!nativeEnabled()) return
        val root = mixedRepo()
        val outcome = runBlocking { runPrepared(root, R_SOURCE, PYTHON_SOURCE) }
        assertTrue(outcome.passed)
        assertEquals(setOf("RPROJECT", "PYTHON"), outcome.plan.groups.map { it.systemId }.toSet())
        assertTrue("R marker was not written", markerExists(root, R_MARKER))
        assertTrue("Python marker was not written", markerExists(root, PYTHON_MARKER))
    }

    fun testOneFailingGroupPreservesAggregateFailureAfterBothGroupsRan() {
        if (!nativeEnabled()) return
        val root = mixedRepo()
        File(root, PYTHON_SOURCE).writeText(
            File(root, PYTHON_SOURCE).readText().replace(
                "assert value() == 1",
                "raise AssertionError('requested mixed fixture failure')",
            ),
        )
        val outcome = runBlocking { runPrepared(root, R_SOURCE, PYTHON_SOURCE) }
        assertFalse(outcome.passed)
        assertTrue("R group did not finish after the Python failure", markerExists(root, R_MARKER))
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
        val source = CliConformanceRepository.configured.fixture("mixed-r-python")
        val root = createTempDirectory("affected-mixed-r-python").toFile()
        temporaryRoots += root
        source.listFiles().orEmpty().forEach { child ->
            check(child.copyRecursively(File(root, child.name), overwrite = true))
        }
        return root
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
        const val R_SOURCE = "stats/R/value.R"
        const val PYTHON_SOURCE = "analysis/tests/test_value.py"
        const val R_MARKER = "mixed-r.marker"
        const val PYTHON_MARKER = "mixed-python.marker"
        const val SESSION_TIMEOUT_MILLIS = 300_000L
        const val MAX_MARKER_DEPTH = 12
        val COPIED_ROOTS = listOf("stats", "analysis")
    }
}
