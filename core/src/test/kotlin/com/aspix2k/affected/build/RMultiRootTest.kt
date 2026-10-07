package com.aspix2k.affected.build

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RMultiRootTest {

    @Test
    fun `two R roots without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("r-multi").toFile()
        val pkg = File(base, "analysis/pkg").also {
            File(it, "tests/testthat").mkdirs()
            File(it, "tests/testthat/test-a.R").writeText("")
            File(it, "DESCRIPTION").writeText("Package: probe\nTitle: Probe\n")
        }
        val renv = File(base, "analysis/report").also {
            File(it, "tests/testthat").mkdirs()
            File(it, "tests/testthat/test-b.R").writeText("")
            File(it, "renv.lock").writeText("{}")
        }
        val system = IdeRBuildSystem()
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(setOf(pkg, renv).map { it.invariantSeparatorsPath }.toSet(), modules.map { it.root }.toSet())
        assertEquals(setOf(pkg, renv).map { it.invariantSeparatorsPath }.toSet(), plan.groups.map { it.root }.toSet())
        val commands = plan.groups.associate { group -> group.root to rCommands(File(group.root), group.tasks) }
        assertEquals(
            "testthat::test_local(\".\")",
            commands.getValue(pkg.invariantSeparatorsPath).single().arguments.last(),
        )
        assertEquals(
            "testthat::test_dir(\"tests/testthat\")",
            commands.getValue(renv.invariantSeparatorsPath).single().arguments.last(),
        )
    }
}
