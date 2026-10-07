package com.aspix2k.affected.build

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NinjaMultiRootTest {

    @Test
    fun `two Ninja roots without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("ninja-multi").toFile()
        val first = root(base, "native", "build test: phony\n")
        val second = root(base, "apps/tools", "build check: phony\n")
        val expected = setOf(first, second).map { it.invariantSeparatorsPath }.toSet()
        val system = IdeNinjaBuildSystem()
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(expected, modules.map { it.root }.toSet())
        assertEquals(expected, plan.groups.map { it.root }.toSet())
        val commands = plan.groups.associate { group ->
            group.root to ninjaCommands(File(group.root), group.tasks).single().arguments
        }
        assertEquals(listOf("ninja", "test"), commands.getValue(first.invariantSeparatorsPath))
        assertEquals(listOf("ninja", "check"), commands.getValue(second.invariantSeparatorsPath))
    }

    @Test
    fun `a change in one root plans only that root`() {
        val base = createTempDirectory("ninja-one").toFile()
        root(base, "native", "build test: phony\n")
        val second = root(base, "apps/tools", "build check: phony\n")
        val system = IdeNinjaBuildSystem()
        val modules = system.modules(multiRootProject(base))
        val changed = modules.filter { it.root == second.invariantSeparatorsPath }

        val plan = TaskPlanner.plan(changed.map { ModuleGraph.Node(it, system).info() }, emptyList())

        val group = plan.groups.single()
        assertEquals(second.invariantSeparatorsPath, group.root)
        assertEquals(
            listOf("ninja", "check"),
            ninjaCommands(File(group.root), group.tasks).single().arguments,
        )
    }

    @Test
    fun `a base manifest and a single nested root keep one root`() {
        val withBase = createTempDirectory("ninja-base").toFile()
        val baseRoot = root(withBase, ".", "build test: phony\n")
        root(withBase, "native", "build check: phony\n")
        val nestedOnly = createTempDirectory("ninja-nested").toFile()
        val nested = root(nestedOnly, "native", "build test: phony\n")

        assertEquals(
            listOf(baseRoot.invariantSeparatorsPath),
            IdeNinjaBuildSystem().modules(multiRootProject(withBase)).map { it.root },
        )
        assertEquals(
            listOf(nested.invariantSeparatorsPath),
            IdeNinjaBuildSystem().modules(multiRootProject(nestedOnly)).map { it.root },
        )
    }

    private fun root(base: File, path: String, manifest: String): File = File(base, path).normalize().also {
        it.mkdirs()
        File(it, "build.ninja").writeText(manifest)
    }
}
