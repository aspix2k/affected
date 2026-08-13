package com.aspix2k.affected

import com.aspix2k.affected.build.CargoMetadata
import com.aspix2k.affected.build.cargoBuildScriptLayout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CargoMetadataTest {

    @Test
    fun `custom build targets are detected from Cargo metadata`() {
        val metadata = """
            {"packages":[{"name":"alpha","manifest_path":"/repo/alpha/Cargo.toml","dependencies":[],"targets":[{"kind":["custom-build"]}]}]}
        """.trimIndent()

        assertEquals(true, CargoMetadata.hasCustomBuild(metadata))
        assertEquals(false, CargoMetadata.hasCustomBuild(metadata.replace("custom-build", "lib")))
        assertEquals(null, CargoMetadata.hasCustomBuild("{}"))
        assertEquals(null, CargoMetadata.hasCustomBuild(metadata.replace("\"custom-build\"", "{}")))
    }

    @Test
    fun `build script presence changes Cargo cache identity`() {
        val root = createTempDirectory("cargo-build-layout").toFile()
        val manifest = File(root, "Cargo.toml").apply { writeText("[package]\nname='fixture'") }
        val before = cargoBuildScriptLayout(root, listOf(manifest))

        File(root, "build.rs").writeText("fn main() {}")

        assertTrue(before != cargoBuildScriptLayout(root, listOf(manifest)))
    }

    private fun workspace(): File {
        val root = File.createTempFile("cargo", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        File(root, "crates/core/src").mkdirs()
        File(root, "crates/app/src").mkdirs()
        File(root, "crates/core/src/lib.rs").writeText(
            """
            pub fn add(a: i32, b: i32) -> i32 { a + b }

            #[cfg(test)]
            mod tests {
                #[test]
                fn works() { assert_eq!(super::add(1, 2), 3); }
            }
            """.trimIndent()
        )
        File(root, "crates/app/src/main.rs").writeText("fn main() {}")
        return root
    }

    private fun metadata(root: File): String = """
        {
          "packages": [
            {
              "name": "core-lib",
              "manifest_path": "${root.invariantSeparatorsPath}/crates/core/Cargo.toml",
              "dependencies": [],
              "targets": [{"kind":["lib"],"doctest":true}]
            },
            {
              "name": "app",
              "manifest_path": "${root.invariantSeparatorsPath}/crates/app/Cargo.toml",
              "dependencies": [
                { "name": "core-lib" },
                { "name": "serde" }
              ],
              "targets": [{"kind":["bin"],"doctest":false}]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `workspace packages become modules`() {
        val root = workspace()

        val modules = CargoMetadata.parse(metadata(root), root.path)

        assertEquals(setOf("core-lib", "app"), modules.map { it.id }.toSet())
    }

    @Test
    fun `external dependencies are excluded from the graph`() {
        val root = workspace()

        val app = CargoMetadata.parse(metadata(root), root.path).single { it.id == "app" }

        assertEquals(
            setOf("${root.invariantSeparatorsPath}|core-lib"),
            app.dependencies,
            "serde comes from the registry, not the workspace, and cannot be a consumer",
        )
    }

    @Test
    fun `every package stays runnable because cargo test also builds it`() {
        val root = workspace()

        val modules = CargoMetadata.parse(metadata(root), root.path)

        assertTrue(modules.single { it.id == "core-lib" }.hasTests)
        assertTrue(modules.single { it.id == "app" }.hasTests)
    }

    @Test
    fun `Cargo metadata distinguishes doctested libraries from other targets`() {
        val root = workspace()

        val modules = CargoMetadata.parse(metadata(root), root.path) { hasDoctests ->
            if (hasDoctests) "nextest-with-docs" else "nextest-without-docs"
        }

        assertEquals("nextest-with-docs", modules.single { it.id == "core-lib" }.testTask)
        assertEquals("nextest-without-docs", modules.single { it.id == "app" }.testTask)
    }

    @Test
    fun `Cargo metadata rejects non-boolean doctest capability`() {
        val root = workspace()

        assertEquals(emptyList(), CargoMetadata.parse(metadata(root).replace(":true", ":\"true\""), root.path))
    }

    @Test
    fun `malformed output does not crash parsing`() {
        assertEquals(emptyList(), CargoMetadata.parse("not json at all", "/repo"))
        assertEquals(emptyList(), CargoMetadata.parse("", "/repo"))
    }
}
