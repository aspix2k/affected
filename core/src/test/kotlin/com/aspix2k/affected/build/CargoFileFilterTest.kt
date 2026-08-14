package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CargoFileFilterTest {

    @Test
    fun `nextest receives a file filter for a changed rust source`() {
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
        ).first()

        assertEquals("-E", command.arguments[command.arguments.indexOf("-E")])
        assertEquals("file(crates/core/src/lib.rs)", command.arguments[command.arguments.indexOf("-E") + 1])
        assertTrue(command.arguments.contains("-p"))
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
        ).first()

        assertFalse(command.arguments.contains("-E"))
        assertTrue(command.arguments.contains("--workspace"))
    }

    @Test
    fun `a single first-level nested Cargo project is the root`() {
        val base = createTempDirectory("cargo-nested").toFile()
        val nested = File(base, "backend")
        cargoToml().copyRecursively(nested)

        assertEquals(nested.canonicalFile, cargoProjectRoot(base)?.canonicalFile)
    }

    @Test
    fun `several first-level nested Cargo projects stay off`() {
        val base = createTempDirectory("cargo-many").toFile()
        cargoToml().copyRecursively(File(base, "backend"))
        cargoToml().copyRecursively(File(base, "tools"))

        assertNull(cargoProjectRoot(base))
    }

    @Test
    fun `a deeper nested Cargo project stays off`() {
        val base = createTempDirectory("cargo-deep").toFile()
        cargoToml().copyRecursively(File(base, "src/backend"))

        assertNull(cargoProjectRoot(base))
    }

    private fun cargoToml(): File {
        val root = createTempDirectory("cargo-toml").toFile()
        File(root, "Cargo.toml").writeText("[package]\nname = \"probe\"\nversion = \"0.1.0\"\nedition = \"2021\"\n")
        return root
    }
}
