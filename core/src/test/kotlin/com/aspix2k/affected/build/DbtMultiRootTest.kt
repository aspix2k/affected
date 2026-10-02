package com.aspix2k.affected.build

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DbtMultiRootTest {

    @Test
    fun `two Dbt roots without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("dbt-multi").toFile()
        val first = root(base, "native", "one")
        val second = root(base, "apps/tools", "two")
        val expected = setOf(first, second).map { it.invariantSeparatorsPath }.toSet()
        val system = DbtBuildSystem()
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(expected, modules.map { it.root }.toSet())
        assertEquals(expected, plan.groups.map { it.root }.toSet())
        val commands = plan.groups.associate { group ->
            group.root to dbtCommands(group.tasks).single().arguments
        }
        assertEquals(
            listOf("dbt", "test", "--project-dir", ".", "--profiles-dir", "."),
            commands.getValue(first.invariantSeparatorsPath),
        )
        assertEquals(
            listOf("dbt", "test", "--project-dir", ".", "--profiles-dir", "."),
            commands.getValue(second.invariantSeparatorsPath),
        )
    }

    @Test
    fun `a change in one root plans only that root`() {
        val base = createTempDirectory("dbt-one").toFile()
        root(base, "native", "one")
        val second = root(base, "apps/tools", "two")
        val system = DbtBuildSystem()
        val modules = system.modules(multiRootProject(base))
        val changed = modules.filter { it.root == second.invariantSeparatorsPath }

        val plan = TaskPlanner.plan(changed.map { ModuleGraph.Node(it, system).info() }, emptyList())

        val group = plan.groups.single()
        assertEquals(second.invariantSeparatorsPath, group.root)
        assertEquals(
            listOf("dbt", "test", "--project-dir", ".", "--profiles-dir", "."),
            dbtCommands(group.tasks).single().arguments,
        )
    }

    @Test
    fun `a base manifest and a single nested root keep one root`() {
        val withBase = createTempDirectory("dbt-base").toFile()
        val baseRoot = root(withBase, ".", "one")
        root(withBase, "native", "two")
        val nestedOnly = createTempDirectory("dbt-nested").toFile()
        val nested = root(nestedOnly, "native", "one")

        assertEquals(
            listOf(baseRoot.invariantSeparatorsPath),
            DbtBuildSystem().modules(multiRootProject(withBase)).map { it.root },
        )
        assertEquals(
            listOf(nested.invariantSeparatorsPath),
            DbtBuildSystem().modules(multiRootProject(nestedOnly)).map { it.root },
        )
    }

    private fun root(base: File, path: String, name: String): File = File(base, path).normalize().also {
        it.mkdirs()
        File(it, "dbt_project.yml").writeText("name: $name\nprofile: $name\n")
        File(it, "profiles.yml").writeText(
            "$name:\n  target: dev\n  outputs:\n    dev:\n      type: duckdb\n      path: local.duckdb\n",
        )
    }
}
