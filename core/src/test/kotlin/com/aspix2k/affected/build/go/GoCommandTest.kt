package com.aspix2k.affected.build.go

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.BuildSystem
import com.aspix2k.affected.build.ChangeAwareSuspendingBuildSystem
import com.intellij.openapi.project.Project
import org.junit.Assume.assumeTrue
import java.io.File
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoCommandTest {

    @Test
    fun `a changed Go test file keeps the package test command`() {
        val command = goCommands(listOf("example.com/alpha:test")).single()
        val system: BuildSystem = GoBuildSystem()

        assertEquals(listOf("go", "test", "example.com/alpha"), command.arguments)
        assertFalse(system is ChangeAwareSuspendingBuildSystem)
    }

    @Test
    fun `constrained changed Go test files keep the package test command`() {
        val root = createTempDirectory("go-build-tag").toFile()
        val alpha = File(root, "alpha").apply { mkdirs() }
        val module = BuildModule(
            "example.com/alpha",
            root.path,
            listOf(alpha.path),
            GoPackages.TEST,
            GoPackages.COMPILE,
            true,
        )
        val graph = ModuleGraph(listOf(ModuleGraph.Node(module, GoBuildSystem())))

        listOf(
            "alpha_test.go" to "package alpha\n",
            "excluded_test.go" to "//go:build affected_never\n\npackage alpha\n",
            "alpha_windows_test.go" to "package alpha\n",
            "alpha_arm64_test.go" to "package alpha\n",
            "alpha_windows_arm64_test.go" to "package alpha\n",
        ).forEach { (name, source) ->
            val changed = File(alpha, name).apply { writeText(source) }
            val plan = TaskPlanner.plan(graph.nodesFor(changed).map(ModuleGraph.Node::info), emptyList())
            val command = goCommands(plan.groups.single().tasks).single()

            assertEquals(listOf("go", "test", "example.com/alpha"), command.arguments, name)
            assertFalse("-run" in command.arguments, name)
        }
    }

    @Test
    fun `a Go production change keeps the package test command`() {
        val root = createTempDirectory("go-src-full").toFile()
        val source = File(root, "alpha/alpha.go").apply {
            parentFile.mkdirs()
            writeText("package alpha\nfunc Value() int { return 1 }\n")
        }
        File(root, "alpha/alpha_test.go").writeText(
            "package alpha\nimport \"testing\"\nfunc TestValue(t *testing.T) {}\n",
        )
        val module = BuildModule(
            "example.com/alpha",
            root.path,
            listOf(File(root, "alpha").path),
            GoPackages.TEST,
            GoPackages.COMPILE,
            true,
        )

        val graph = ModuleGraph(listOf(ModuleGraph.Node(module, GoBuildSystem())))
        val plan = TaskPlanner.plan(graph.nodesFor(source).map(ModuleGraph.Node::info), emptyList())
        val command = goCommands(plan.groups.single().tasks).single()

        assertEquals(listOf("go", "test", "example.com/alpha"), command.arguments)
    }

    @Test
    fun `a single first-level nested Go module is the root`() {
        val base = createTempDirectory("go-nested").toFile()
        val nested = File(base, "backend")
        goMod().copyRecursively(nested)

        assertEquals(nested.canonicalFile, goProjectRoots(base).singleOrNull()?.canonicalFile)
    }

    @Test
    fun `several nested Go modules are all roots`() {
        val base = createTempDirectory("go-many").toFile()
        goMod().copyRecursively(File(base, "backend"))
        goMod().copyRecursively(File(base, "tools"))

        assertEquals(
            listOf(File(base, "backend"), File(base, "tools")).map(File::getCanonicalFile),
            goProjectRoots(base).map(File::getCanonicalFile),
        )
    }

    @Test
    fun `a second-level nested Go module is the root`() {
        val base = createTempDirectory("go-deep").toFile()
        goMod().copyRecursively(File(base, "src/backend"))

        assertEquals(listOf(File(base, "src/backend").canonicalFile), goProjectRoots(base).map(File::getCanonicalFile))
    }

    @Test
    fun `a Go module deeper than three levels stays off`() {
        val base = createTempDirectory("go-too-deep").toFile()
        goMod().copyRecursively(File(base, "a/b/c/d"))

        assertEquals(emptyList(), goProjectRoots(base))
    }

    @Test
    fun `two Go modules without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("go-multi").toFile()
        val roots = listOf("a", "b").map { File(base, "services/$it") }
        roots.forEach { goMod().copyRecursively(it) }
        val system = GoBuildSystem()
        val project = project(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), modules.map { it.root }.toSet())
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), plan.groups.map { it.root }.toSet())
        plan.groups.forEach { assertEquals(listOf("go", "test", "./..."), goCommands(it.tasks).single().arguments) }
    }

    @Test
    fun `Go modules nested below a module are separate roots and directories Go ignores are not`() {
        val base = createTempDirectory("go-below").toFile().canonicalFile
        goMod().copyRecursively(base, overwrite = true)
        listOf("exp", "zapgrpc/internal/test", "vendor/dep", "testdata/sample", "_ignored", ".hidden", "exp/vendor/dep")
            .forEach { goMod().copyRecursively(File(base, it)) }

        assertEquals(
            listOf("", "exp", "zapgrpc/internal/test").map { File(base, it).canonicalFile },
            goModuleRoots(base).map(File::getCanonicalFile),
        )
    }

    @Test
    fun `a single Go module has no nested roots`() {
        val base = goMod().canonicalFile
        File(base, "pkg").mkdirs()

        assertEquals(listOf(base), goModuleRoots(base).map(File::getCanonicalFile))
    }

    @Test
    fun `a file in a nested Go module is owned by it and runs with its own command`() {
        assumeTrue(runCatching { ProcessBuilder("go", "version").start().waitFor() == 0 }.getOrDefault(false))
        val base = createTempDirectory("go-owner").toFile().canonicalFile
        File(base, "go.mod").writeText("module example.com/root\n\ngo 1.21\n")
        File(base, "root.go").writeText("package root\n")
        File(base, "exp/go.mod").apply { parentFile.mkdirs() }.writeText("module example.com/root/exp\n\ngo 1.21\n")
        File(base, "exp/sub/exp.go").apply { parentFile.mkdirs() }.writeText("package sub\n")
        File(base, "vendor/dep/go.mod").apply { parentFile.mkdirs() }.writeText("module example.com/dep\n\ngo 1.21\n")
        File(base, "testdata/sample/go.mod").apply { parentFile.mkdirs() }.writeText("module example.com/sample\n")
        val system = GoBuildSystem()
        val modules = system.modules(project(base))
        val graph = ModuleGraph(modules.map { ModuleGraph.Node(it, system) })

        assertEquals(
            setOf(base.invariantSeparatorsPath, File(base, "exp").invariantSeparatorsPath),
            modules.map { it.root }.toSet(),
        )
        assertEquals(setOf("example.com/root", "example.com/root/exp/sub"), modules.map { it.id }.toSet())
        val owners = graph.nodesFor(File(base, "exp/sub/exp.go"))
        assertEquals(listOf("example.com/root/exp/sub"), owners.map(ModuleGraph.Node::id))
        val plan = TaskPlanner.plan(owners.map(ModuleGraph.Node::info), emptyList())
        val group = plan.groups.single()
        assertEquals(File(base, "exp").invariantSeparatorsPath, group.root)
        assertEquals(listOf("go", "test", "example.com/root/exp/sub"), goCommands(group.tasks).single().arguments)
        assertEquals(
            listOf("example.com/root"),
            graph.nodesFor(File(base, "root.go")).map(ModuleGraph.Node::id),
        )
    }

    private fun project(root: File): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getBasePath" -> root.path
            else -> error("Unexpected Project call: ${method.name}")
        }
    } as Project

    private fun goMod(): File {
        val root = createTempDirectory("go-mod").toFile()
        File(root, "go.mod").writeText("module example.com/probe\n\ngo 1.26\n")
        return root
    }
}
