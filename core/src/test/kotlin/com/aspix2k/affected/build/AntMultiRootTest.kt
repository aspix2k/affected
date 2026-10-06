package com.aspix2k.affected.build

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AntMultiRootTest {

    @Test
    fun `two Ant roots without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("ant-multi").toFile()
        val first = root(base, "native", "<project><target name=\"test\"/></project>")
        val second = root(base, "apps/tools", "<project><target name=\"junit\"/></project>")
        val expected = setOf(first, second).map { it.invariantSeparatorsPath }.toSet()
        val system = IdeAntBuildSystem()
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(expected, modules.map { it.root }.toSet())
        assertEquals(expected, plan.groups.map { it.root }.toSet())
        val commands = plan.groups.associate { group ->
            group.root to antCommands(File(group.root), group.tasks).single().arguments
        }
        assertEquals(listOf("ant", "test"), commands.getValue(first.invariantSeparatorsPath))
        assertEquals(listOf("ant", "junit"), commands.getValue(second.invariantSeparatorsPath))
    }

    @Test
    fun `a change in one root plans only that root`() {
        val base = createTempDirectory("ant-one").toFile()
        root(base, "native", "<project><target name=\"test\"/></project>")
        val second = root(base, "apps/tools", "<project><target name=\"junit\"/></project>")
        val system = IdeAntBuildSystem()
        val modules = system.modules(multiRootProject(base))
        val changed = modules.filter { it.root == second.invariantSeparatorsPath }

        val plan = TaskPlanner.plan(changed.map { ModuleGraph.Node(it, system).info() }, emptyList())

        val group = plan.groups.single()
        assertEquals(second.invariantSeparatorsPath, group.root)
        assertEquals(
            listOf("ant", "junit"),
            antCommands(File(group.root), group.tasks).single().arguments,
        )
    }

    @Test
    fun `a base manifest and a single nested root keep one root`() {
        val withBase = createTempDirectory("ant-base").toFile()
        val baseRoot = root(withBase, ".", "<project><target name=\"test\"/></project>")
        root(withBase, "native", "<project><target name=\"junit\"/></project>")
        val nestedOnly = createTempDirectory("ant-nested").toFile()
        val nested = root(nestedOnly, "native", "<project><target name=\"test\"/></project>")

        assertEquals(
            listOf(baseRoot.invariantSeparatorsPath),
            IdeAntBuildSystem().modules(multiRootProject(withBase)).map { it.root },
        )
        assertEquals(
            listOf(nested.invariantSeparatorsPath),
            IdeAntBuildSystem().modules(multiRootProject(nestedOnly)).map { it.root },
        )
    }

    private fun root(base: File, path: String, manifest: String): File = File(base, path).normalize().also {
        it.mkdirs()
        File(it, "build.xml").writeText(manifest)
    }
}
