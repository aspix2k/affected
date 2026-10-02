package com.aspix2k.affected.build

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MesonMultiRootTest {

    @Test
    fun `two Meson roots without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("meson-multi").toFile()
        val first = root(base, "native", "project('one', 'c')\ntest('t', exe)\n")
        val second = root(base, "apps/tools", "project('two', 'c')\ntest('t', exe)\n")
        val expected = setOf(first, second).map { it.invariantSeparatorsPath }.toSet()
        val system = MesonBuildSystem()
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(expected, modules.map { it.root }.toSet())
        assertEquals(expected, plan.groups.map { it.root }.toSet())
        val commands = plan.groups.associate { group ->
            group.root to mesonCommands(File(group.root), group.tasks).last().arguments
        }
        assertEquals(listOf("meson", "test", "-C", "build"), commands.getValue(first.invariantSeparatorsPath))
        assertEquals(listOf("meson", "test", "-C", "build"), commands.getValue(second.invariantSeparatorsPath))
    }

    @Test
    fun `a change in one root plans only that root`() {
        val base = createTempDirectory("meson-one").toFile()
        root(base, "native", "project('one', 'c')\ntest('t', exe)\n")
        val second = root(base, "apps/tools", "project('two', 'c')\ntest('t', exe)\n")
        val system = MesonBuildSystem()
        val modules = system.modules(multiRootProject(base))
        val changed = modules.filter { it.root == second.invariantSeparatorsPath }

        val plan = TaskPlanner.plan(changed.map { ModuleGraph.Node(it, system).info() }, emptyList())

        val group = plan.groups.single()
        assertEquals(second.invariantSeparatorsPath, group.root)
        assertEquals(
            listOf("meson", "test", "-C", "build"),
            mesonCommands(File(group.root), group.tasks).last().arguments,
        )
    }

    @Test
    fun `a base manifest and a single nested root keep one root`() {
        val withBase = createTempDirectory("meson-base").toFile()
        val baseRoot = root(withBase, ".", "project('one', 'c')\ntest('t', exe)\n")
        root(withBase, "native", "project('two', 'c')\ntest('t', exe)\n")
        val nestedOnly = createTempDirectory("meson-nested").toFile()
        val nested = root(nestedOnly, "native", "project('one', 'c')\ntest('t', exe)\n")

        assertEquals(
            listOf(baseRoot.invariantSeparatorsPath),
            MesonBuildSystem().modules(multiRootProject(withBase)).map { it.root },
        )
        assertEquals(
            listOf(nested.invariantSeparatorsPath),
            MesonBuildSystem().modules(multiRootProject(nestedOnly)).map { it.root },
        )
    }

    private fun root(base: File, path: String, manifest: String): File = File(base, path).normalize().also {
        it.mkdirs()
        File(it, "meson.build").writeText(manifest)
    }
}
