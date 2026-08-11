package com.aspix2k.affected

import com.aspix2k.affected.build.CargoMetadata
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CargoMetadataTest {

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
              "dependencies": []
            },
            {
              "name": "app",
              "manifest_path": "${root.invariantSeparatorsPath}/crates/app/Cargo.toml",
              "dependencies": [
                { "name": "core-lib" },
                { "name": "serde" }
              ]
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
    fun `malformed output does not crash parsing`() {
        assertEquals(emptyList(), CargoMetadata.parse("not json at all", "/repo"))
        assertEquals(emptyList(), CargoMetadata.parse("", "/repo"))
    }
}
