package com.aspix2k.affected.build

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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

        assertEquals(nested.canonicalFile, goProjectRoot(base)?.canonicalFile)
    }

    @Test
    fun `several first-level nested Go modules stay off`() {
        val base = createTempDirectory("go-many").toFile()
        goMod().copyRecursively(File(base, "backend"))
        goMod().copyRecursively(File(base, "tools"))

        assertNull(goProjectRoot(base))
    }

    @Test
    fun `a deeper nested Go module stays off`() {
        val base = createTempDirectory("go-deep").toFile()
        goMod().copyRecursively(File(base, "src/backend"))

        assertNull(goProjectRoot(base))
    }

    private fun goMod(): File {
        val root = createTempDirectory("go-mod").toFile()
        File(root, "go.mod").writeText("module example.com/probe\n\ngo 1.26\n")
        return root
    }
}
