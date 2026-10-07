package com.aspix2k.affected.build

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinToolchainMultiRootTest {

    @Test
    fun `two toolchain projects without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("toolchain-multi").toFile()
        val roots = listOf("api", "worker").map { toolchain(base, "services/$it") }
        val system = IdeKotlinToolchainBuildSystem()
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), modules.map { it.root }.toSet())
        assertEquals(setOf("api", "worker"), modules.map { it.id }.toSet())
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), plan.groups.map { it.root }.toSet())
        plan.groups.forEach { group ->
            val command = kotlinToolchainCommands(File(group.root), group.tasks).single()
            assertEquals(listOf("./kotlin", "test"), command.arguments, group.root)
        }
    }

    @Test
    fun `a Gradle settings file keeps a directory out of the toolchain roots`() {
        val base = createTempDirectory("toolchain-multi-gradle").toFile()
        val toolchain = toolchain(base, "services/api")
        toolchain(base, "services/legacy").also { File(it, "settings.gradle.kts").writeText("") }

        assertEquals(listOf(toolchain.canonicalFile), kotlinToolchainProjectRoots(base).map(File::getCanonicalFile))
    }

    private fun toolchain(base: File, path: String): File = File(base, path).also {
        File(it, "test").mkdirs()
        File(it, "test/ProbeTest.kt").writeText("")
        File(it, "project.yaml").writeText("")
        File(it, "kotlin").writeText("")
    }
}
