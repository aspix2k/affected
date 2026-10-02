package com.aspix2k.affected.build

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AtlasMultiRootTest {

    @Test
    fun `two Atlas roots without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("atlas-multi").toFile()
        val first = root(base, "native", "one")
        val second = root(base, "apps/tools", "two")
        val expected = setOf(first, second).map { it.invariantSeparatorsPath }.toSet()
        val system = AtlasBuildSystem()
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(expected, modules.map { it.root }.toSet())
        assertEquals(expected, plan.groups.map { it.root }.toSet())
        val commands = plan.groups.associate { group ->
            group.root to atlasCommands(group.tasks).single().arguments
        }
        assertEquals(listOf("atlas", "migrate", "validate"), commands.getValue(first.invariantSeparatorsPath))
        assertEquals(listOf("atlas", "migrate", "validate"), commands.getValue(second.invariantSeparatorsPath))
    }

    @Test
    fun `a change in one root plans only that root`() {
        val base = createTempDirectory("atlas-one").toFile()
        root(base, "native", "one")
        val second = root(base, "apps/tools", "two")
        val system = AtlasBuildSystem()
        val modules = system.modules(multiRootProject(base))
        val changed = modules.filter { it.root == second.invariantSeparatorsPath }

        val plan = TaskPlanner.plan(changed.map { ModuleGraph.Node(it, system).info() }, emptyList())

        val group = plan.groups.single()
        assertEquals(second.invariantSeparatorsPath, group.root)
        assertEquals(
            listOf("atlas", "migrate", "validate"),
            atlasCommands(group.tasks).single().arguments,
        )
    }

    @Test
    fun `a base manifest and a single nested root keep one root`() {
        val withBase = createTempDirectory("atlas-base").toFile()
        val baseRoot = root(withBase, ".", "one")
        root(withBase, "native", "two")
        val nestedOnly = createTempDirectory("atlas-nested").toFile()
        val nested = root(nestedOnly, "native", "one")

        assertEquals(
            listOf(baseRoot.invariantSeparatorsPath),
            AtlasBuildSystem().modules(multiRootProject(withBase)).map { it.root },
        )
        assertEquals(
            listOf(nested.invariantSeparatorsPath),
            AtlasBuildSystem().modules(multiRootProject(nestedOnly)).map { it.root },
        )
    }

    private fun root(base: File, path: String, name: String): File = File(base, path).normalize().also {
        it.mkdirs()
        File(it, "atlas.hcl").writeText("variable \"$name\" {\n  type = string\n  default = \"local\"\n}\n")
    }
}
