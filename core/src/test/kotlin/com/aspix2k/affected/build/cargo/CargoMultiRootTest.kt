package com.aspix2k.affected.build.cargo

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import com.aspix2k.affected.build.IdeCargoBuildSystem
import com.aspix2k.affected.build.multiRootProject
import com.aspix2k.affected.build.testSnapshotRoot
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
        val system = IdeCargoBuildSystem()
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), modules.map { it.root }.toSet())
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), plan.groups.map { it.root }.toSet())
        plan.groups.forEach { group ->
            val command = cargoCommands(group.root, group.tasks, snapshotRoot = testSnapshotRoot).single()
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

    @Test
    fun `a crate outside the workspace is its own root and members are not duplicated`() {
        val base = createTempDirectory("cargo-independent").toFile()
        File(base, "Cargo.toml").writeText(
            "[workspace]\nmembers = [\"crates/*\"]\nexclude = [\"crates/legacy\"]\n\n" +
                "[dependencies]\nshared = { path = \"libs/shared\" }\n",
        )
        crate(base, "crates/core", "core")
        crate(base, "crates/legacy", "legacy")
        crate(base, "libs/shared", "shared")
        crate(base, "tools/standalone", "standalone")
        crate(base, "tools/standalone/fuzz", "fuzz").also {
            File(it, "Cargo.toml").appendText("\n[package.metadata]\ncargo-fuzz = true\n")
        }
        crate(base, "tools/own-workspace", "own").also {
            File(it, "Cargo.toml").appendText("\n[workspace]\n")
        }

        assertEquals(
            listOf("", "crates/legacy", "tools/own-workspace", "tools/standalone").map { File(base, it).canonicalFile },
            cargoProjectRoots(base).map(File::getCanonicalFile),
        )
    }

    @Test
    fun `a nested crate below a root without a workspace is its own root`() {
        val base = crate(createTempDirectory("cargo-no-workspace").toFile(), ".", "app")
        crate(base, "examples/demo", "demo")

        assertEquals(
            listOf(base, File(base, "examples/demo")).map(File::getCanonicalFile),
            cargoProjectRoots(base).map(File::getCanonicalFile),
        )
    }

    private fun crate(base: File, path: String, name: String): File = File(base, path).also {
        it.mkdirs()
        File(it, "Cargo.toml").writeText("[package]\nname = \"$name\"\nversion = \"0.1.0\"\nedition = \"2021\"\n")
    }
}
