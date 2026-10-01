package com.aspix2k.affected.build.deno

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import com.aspix2k.affected.build.multiRootProject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DenoMultiRootTest {

    @Test
    fun `two Deno projects without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("deno-multi").toFile()
        val tasked = deno(base, "apps/web", """{ "tasks": { "test": "deno test -A" } }""")
        val plain = deno(base, "apps/admin", "{}")
        val expected = setOf(tasked, plain).map { it.invariantSeparatorsPath }.toSet()
        val system = DenoBuildSystem()
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(expected, modules.map { it.root }.toSet())
        assertEquals(expected, plan.groups.map { it.root }.toSet())
        val commands = plan.groups.associate { group -> group.root to denoCommands(File(group.root), group.tasks) }
        assertEquals(
            listOf("deno", "task", "test"),
            commands.getValue(tasked.invariantSeparatorsPath).single().arguments,
        )
        assertEquals(listOf("deno", "test"), commands.getValue(plain.invariantSeparatorsPath).single().arguments)
    }

    @Test
    fun `a Node package that owns tests keeps its directory out of the Deno roots`() {
        val base = createTempDirectory("deno-multi-node").toFile()
        deno(base, "apps/web", "{}").also {
            File(it, "package.json").writeText("""{ "scripts": { "test": "jest" } }""")
        }
        val plain = deno(base, "apps/admin", "{}")

        assertEquals(listOf(plain.canonicalFile), denoProjectRoots(base).map(File::getCanonicalFile))
    }

    private fun deno(base: File, path: String, config: String): File = File(base, path).also {
        it.mkdirs()
        File(it, "deno.json").writeText(config)
        File(it, "main_test.ts").writeText("")
    }
}
