package com.aspix2k.affected

import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.BuildSystem
import com.aspix2k.affected.build.TransitiveTestConsumersBuildSystem
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

    @Test
    fun `dotnet changes reach transitive test projects`() {
        val root = createTempDirectory("module-graph-dotnet").toFile()
        val alpha = module(root, "Alpha", "Alpha").copy(testTask = "build", compileTask = "build")
        val facade = module(root, "Facade", "Facade").copy(
            testTask = "build",
            compileTask = "build",
            dependencies = setOf(alpha.key),
        )
        val tests = module(root, "Facade.Tests", "Facade.Tests").copy(
            compileTask = "build",
            dependencies = setOf(facade.key),
        )
        val hiddenConsumer = module(root, "Hidden.Tests", "Hidden.Tests").copy(compileTask = "build")
        val dotnet = dotnetSystem()
        val graph = ModuleGraph(
            listOf(alpha, facade, tests, hiddenConsumer).map { ModuleGraph.Node(it, dotnet) },
        )

        val changed = File(root, "Alpha/Value.cs").apply { writeText("namespace Alpha;") }
        val consumers = graph.transitiveTestConsumers(setOf(graph.all().first()))

        assertEquals(setOf("Facade.Tests", "Hidden.Tests"), consumers.mapTo(HashSet(), ModuleGraph.Node::id))
        assertEquals(
            listOf("Alpha:build", "Facade.Tests:test", "Hidden.Tests:test"),
            verificationPlan(
                graph,
                ProjectChanges.Result(listOf(changed), emptySet(), setOf(changed), comparedToBase = true),
                checkConsumers = false,
            ).groups.single().tasks,
        )
    }

    @Test
    fun `Composer changes reach transitive PHPUnit packages`() {
        val root = createTempDirectory("module-graph-composer").toFile()
        val alpha = module(root, "affected/alpha", "packages/alpha")
        val beta = module(root, "affected/beta", "packages/beta").copy(dependencies = setOf(alpha.key))
        val composer = transitiveSystem("COMPOSER")
        val graph = ModuleGraph(listOf(alpha, beta).map { ModuleGraph.Node(it, composer) })
        val changed = File(root, "packages/alpha/src/Alpha.php").apply {
            parentFile.mkdirs()
            writeText("<?php")
        }

        assertEquals(
            listOf("affected/alpha:test", "affected/beta:test"),
            verificationPlan(
                graph,
                ProjectChanges.Result(listOf(changed), emptySet(), setOf(changed), comparedToBase = true),
                checkConsumers = false,
            ).groups.single().tasks,
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

    private fun dotnetSystem(): BuildSystem = transitiveSystem("DOTNET")

    private fun transitiveSystem(systemId: String): BuildSystem = object :
        BuildSystem,
        TransitiveTestConsumersBuildSystem {
        override val id: String = systemId
        override val sourceExtensions: Set<String> = emptySet()
        override fun isPresent(project: Project): Boolean = false
        override fun modules(project: Project): List<BuildModule> = emptyList()
        override fun run(project: Project, root: String, tasks: List<String>) = Unit
        override fun runAndWait(project: Project, root: String, tasks: List<String>): Boolean = false
    }
}
