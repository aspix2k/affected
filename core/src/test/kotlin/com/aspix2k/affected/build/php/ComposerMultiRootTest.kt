package com.aspix2k.affected.build.php

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import com.aspix2k.affected.build.multiRootProject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposerMultiRootTest {

    @Test
    fun `two Composer packages without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("composer-multi").toFile()
        val roots = listOf("api", "worker").map { composer(base, "services/$it") }
        val system = ComposerBuildSystem()
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), modules.map { it.root }.toSet())
        assertEquals(setOf("."), modules.map { it.executionId }.toSet())
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), plan.groups.map { it.root }.toSet())
        plan.groups.forEach { group ->
            val owned = modules.filter { it.executionRoot == group.root }
            val command = composerCommands(group.root, group.tasks, owned).single()
            assertEquals(listOf("php", "vendor/bin/phpunit", "./tests"), command.arguments, group.root)
        }
    }

    @Test
    fun `a root package with a PHPUnit configuration runs its configured suites instead of scanning the root`() {
        val base = createTempDirectory("composer-configured").toFile()
        val root = composer(base, ".")
        File(root, "phpunit.xml.dist").writeText("<phpunit/>")
        File(root, "packages/tool/tests").mkdirs()
        File(root, "packages/tool/composer.json").writeText("{\"name\":\"acme/tool\"}")
        val system = ComposerBuildSystem()
        val modules = system.modules(multiRootProject(base))
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        val commands = composerCommands(plan.groups.single().root, plan.groups.single().tasks, modules)

        assertEquals(
            listOf(listOf("php", "vendor/bin/phpunit"), listOf("php", "vendor/bin/phpunit", "./packages/tool")),
            commands.map { it.arguments },
        )
    }

    private fun composer(base: File, path: String): File = File(base, path).also {
        File(it, "tests").mkdirs()
        File(it, "composer.json").writeText("{\"name\":\"acme/app\"}")
    }
}
