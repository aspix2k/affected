package com.aspix2k.affected

import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.BuildSystem
import com.intellij.openapi.project.Project
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleGraphTest {

    @Test
    fun `a root manifest belongs to every module in that build`() {
        val root = createTempDirectory("module-graph-root").toFile()
        val graph = graph(
            module(root, "a", "packages/a"),
            module(root, "b", "packages/b"),
        )

        val owners = graph.nodesFor(File(root, "package.json"))

        assertEquals(setOf("a", "b"), owners.mapTo(HashSet()) { it.id })
    }

    @Test
    fun `a nested build owns its root manifest without widening the outer build`() {
        val root = createTempDirectory("module-graph-nested").toFile()
        val nested = File(root, "web").apply { mkdirs() }
        val graph = ModuleGraph(
            listOf(
                ModuleGraph.Node(module(root, "outer", "app"), system("GRADLE")),
                ModuleGraph.Node(module(nested, "inner", "packages/inner"), system("NODE")),
            ),
        )

        val owners = graph.nodesFor(File(nested, "package.json"))

        assertEquals(listOf("inner"), owners.map { it.id })
    }

    @Test
    fun `shared Gradle content keeps one source-set owner`() {
        val root = createTempDirectory("module-graph-gradle").toFile()
        val content = File(root, "shared").apply { mkdirs() }
        val graph = ModuleGraph(
            listOf(
                ModuleGraph.Node(module(root, "app", "shared"), system("GRADLE")),
                ModuleGraph.Node(module(root, "app.main", "shared"), system("GRADLE")),
            ),
        )

        assertEquals(listOf("app.main"), graph.nodesFor(File(content, "Sample.kt")).map { it.id })
    }

    @Test
    fun `shared CMake content keeps every declared target owner`() {
        val root = createTempDirectory("module-graph-cmake").toFile()
        val content = File(root, "shared").apply { mkdirs() }
        val graph = ModuleGraph(
            listOf(
                ModuleGraph.Node(module(root, "library", "shared"), system("CMAKE")),
                ModuleGraph.Node(module(root, "application", "shared"), system("CMAKE")),
            ),
        )

        assertEquals(
            setOf("library", "application"),
            graph.nodesFor(File(content, "CMakeLists.txt")).mapTo(HashSet()) { it.id },
        )
    }

    private fun graph(vararg modules: BuildModule): ModuleGraph =
        ModuleGraph(modules.map { ModuleGraph.Node(it, system("NODE")) })

    private fun module(root: File, id: String, contentRoot: String): BuildModule = BuildModule(
        id = id,
        root = root.invariantSeparatorsPath,
        contentRoots = listOf(File(root, contentRoot).apply { mkdirs() }.invariantSeparatorsPath),
        testTask = "test",
        compileTask = null,
        hasTests = true,
    )

    private fun system(systemId: String): BuildSystem = object : BuildSystem {
        override val id: String = systemId
        override val sourceExtensions: Set<String> = emptySet()
        override fun isPresent(project: Project): Boolean = false
        override fun modules(project: Project): List<BuildModule> = emptyList()
        override fun run(project: Project, root: String, tasks: List<String>) = Unit
        override fun runAndWait(project: Project, root: String, tasks: List<String>): Boolean = false
    }
}
