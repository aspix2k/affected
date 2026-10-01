package com.aspix2k.affected.build.cargo

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import com.aspix2k.affected.build.multiRootProject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CargoMultiRootTest {

    @Test
    fun `two Cargo projects without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("cargo-multi").toFile()
        val roots = listOf("api", "worker").map { crate(base, "services/$it", it) }
        val system = CargoBuildSystem()
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), modules.map { it.root }.toSet())
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), plan.groups.map { it.root }.toSet())
        plan.groups.forEach { group ->
            val command = cargoCommands(group.root, group.tasks).single()
            assertEquals(listOf("cargo", "test", "--workspace"), command.arguments)
        }
    }

    @Test
    fun `workspace members stay inside their Cargo root`() {
        val base = createTempDirectory("cargo-multi-workspace").toFile()
        val workspace = crate(base, "services/api", "api").also {
            File(it, "Cargo.toml").writeText("[workspace]\nmembers = [\"crates/core\"]\n")
            crate(it, "crates/core", "core")
        }
        crate(base, "services/worker", "worker")

        assertEquals(
            listOf(workspace, File(base, "services/worker")).map(File::getCanonicalFile),
            cargoProjectRoots(base).map(File::getCanonicalFile),
        )
    }

    private fun crate(base: File, path: String, name: String): File = File(base, path).also {
        it.mkdirs()
        File(it, "Cargo.toml").writeText("[package]\nname = \"$name\"\nversion = \"0.1.0\"\nedition = \"2021\"\n")
    }
}
