package com.aspix2k.affected.build

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlcMultiRootTest {

    @Test
    fun `two Sqlc roots without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("sqlc-multi").toFile()
        val first = root(base, "native", "one")
        val second = root(base, "apps/tools", "two")
        val expected = setOf(first, second).map { it.invariantSeparatorsPath }.toSet()
        val system = IdeSqlcBuildSystem()
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(expected, modules.map { it.root }.toSet())
        assertEquals(expected, plan.groups.map { it.root }.toSet())
        val commands = plan.groups.associate { group ->
            group.root to sqlcCommands(group.tasks).single().arguments
        }
        assertEquals(listOf("sqlc", "compile"), commands.getValue(first.invariantSeparatorsPath))
        assertEquals(listOf("sqlc", "compile"), commands.getValue(second.invariantSeparatorsPath))
    }

    @Test
    fun `a change in one root plans only that root`() {
        val base = createTempDirectory("sqlc-one").toFile()
        root(base, "native", "one")
        val second = root(base, "apps/tools", "two")
        val system = IdeSqlcBuildSystem()
        val modules = system.modules(multiRootProject(base))
        val changed = modules.filter { it.root == second.invariantSeparatorsPath }

        val plan = TaskPlanner.plan(changed.map { ModuleGraph.Node(it, system).info() }, emptyList())

        val group = plan.groups.single()
        assertEquals(second.invariantSeparatorsPath, group.root)
        assertEquals(
            listOf("sqlc", "compile"),
            sqlcCommands(group.tasks).single().arguments,
        )
    }

    @Test
    fun `a base manifest and a nested root are both roots`() {
        val withBase = createTempDirectory("sqlc-base").toFile()
        val baseRoot = root(withBase, ".", "one")
        val independent = root(withBase, "native", "two")
        val nestedOnly = createTempDirectory("sqlc-nested").toFile()
        val nested = root(nestedOnly, "native", "one")

        assertEquals(
            listOf(baseRoot, independent).map { it.invariantSeparatorsPath },
            IdeSqlcBuildSystem().modules(multiRootProject(withBase)).map { it.root },
        )
        assertEquals(
            listOf(nested.invariantSeparatorsPath),
            IdeSqlcBuildSystem().modules(multiRootProject(nestedOnly)).map { it.root },
        )
    }

    private fun root(base: File, path: String, schema: String): File = File(base, path).normalize().also {
        it.mkdirs()
        File(it, "sqlc.yaml").writeText(
            "version: \"2\"\nsql:\n  - engine: sqlite\n    schema: $schema.sql\n    queries: query.sql\n",
        )
    }
}
