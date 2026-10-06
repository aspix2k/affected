package com.aspix2k.affected.build.cargo

import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.testSnapshotRoot
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CargoFileFilterTest {

    @Test
    fun `a changed rust source runs its whole package without a filterset`() {
        val root = createTempDirectory("cargo-file-filter").toFile()
        val source = File(root, "crates/core/src/lib.rs").apply {
            parentFile.mkdirs()
            writeText("pub fn value() -> u8 { 1 }\n")
        }
        val task = cargoNextestTask(CargoNextestPlan(CargoNextestMode.PACKAGES, "default", "0.9.143", false))

        val command = cargoCommands(
            root.path,
            listOf("core:$task"),
            BuildChanges(
                files = listOf(source.path),
                exactSelectionEligible = setOf(source.path),
                comparedToBase = true,
            ),
            unsafeCargoExecution = false,
            snapshotRoot = testSnapshotRoot,
        ).first()

        assertFalse(command.arguments.contains("-E"))
        assertEquals(listOf("-p", "core"), command.arguments.takeLast(2))
    }

    @Test
    fun `a new rust source runs its package, not the whole workspace`() {
        val root = createTempDirectory("cargo-added-file").toFile()
        val added = File(root, "crates/core/src/fresh.rs").apply {
            parentFile.mkdirs()
            writeText("pub fn fresh() {}\n")
        }
        val task = cargoNextestTask(CargoNextestPlan(CargoNextestMode.PACKAGES, "default", "0.9.143", false))

        val command = cargoCommands(
            root.path,
            listOf("core:$task"),
            BuildChanges(files = listOf(added.path), exactSelectionEligible = emptySet(), comparedToBase = true),
            unsafeCargoExecution = false,
            snapshotRoot = testSnapshotRoot,
        ).first()

        assertFalse(command.arguments.contains("--workspace"))
        assertEquals(listOf("-p", "core"), command.arguments.takeLast(2))
    }

    @Test
    fun `a workspace-widening cargo change does not add a file filter`() {
        val root = createTempDirectory("cargo-file-workspace").toFile()
        val script = File(root, "build.rs").apply { writeText("fn main() {}") }
        val task = cargoNextestTask(CargoNextestPlan(CargoNextestMode.PACKAGES, "default", "0.9.143", false))

        val command = cargoCommands(
            root.path,
            listOf("core:$task"),
            BuildChanges(
                files = listOf(script.path),
                exactSelectionEligible = setOf(script.path),
                comparedToBase = true,
            ),
            unsafeCargoExecution = false,
            snapshotRoot = testSnapshotRoot,
        ).first()

        assertFalse(command.arguments.contains("-E"))
        assertTrue(command.arguments.contains("--workspace"))
    }

    @Test
    fun `a single first-level nested Cargo project is the root`() {
        val base = createTempDirectory("cargo-nested").toFile()
        val nested = File(base, "backend")
        cargoToml().copyRecursively(nested)

        assertEquals(nested.canonicalFile, cargoProjectRoots(base).singleOrNull()?.canonicalFile)
    }

    @Test
    fun `several nested Cargo projects are all roots`() {
        val base = createTempDirectory("cargo-many").toFile()
        cargoToml().copyRecursively(File(base, "backend"))
        cargoToml().copyRecursively(File(base, "tools"))

        assertEquals(
            listOf(File(base, "backend"), File(base, "tools")).map(File::getCanonicalFile),
            cargoProjectRoots(base).map(File::getCanonicalFile),
        )
    }

    @Test
    fun `a second-level nested Cargo project is the root`() {
        val base = createTempDirectory("cargo-deep").toFile()
        cargoToml().copyRecursively(File(base, "src/backend"))

        assertEquals(
            listOf(File(base, "src/backend").canonicalFile),
            cargoProjectRoots(base).map(File::getCanonicalFile),
        )
    }

    @Test
    fun `a Cargo project deeper than three levels stays off`() {
        val base = createTempDirectory("cargo-too-deep").toFile()
        cargoToml().copyRecursively(File(base, "a/b/c/d"))

        assertEquals(emptyList(), cargoProjectRoots(base))
    }

    private fun cargoToml(): File {
        val root = createTempDirectory("cargo-toml").toFile()
        File(root, "Cargo.toml").writeText("[package]\nname = \"probe\"\nversion = \"0.1.0\"\nedition = \"2021\"\n")
        return root
    }
}
