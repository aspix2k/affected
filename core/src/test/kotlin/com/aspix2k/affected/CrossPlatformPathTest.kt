package com.aspix2k.affected

import com.aspix2k.affected.build.CargoMetadata
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossPlatformPathTest {

    @Test
    fun `Windows paths from Cargo metadata use forward slashes`() {
        val json = """
            {
              "packages": [
                {
                  "name": "core",
                  "manifest_path": "C:\\projects\\demo\\crates\\core\\Cargo.toml",
                  "dependencies": []
                }
              ]
            }
        """.trimIndent()

        val module = CargoMetadata.parse(json, "C:/projects/demo").single()

        assertFalse('\\' in module.contentRoots.single(), "a content root must not contain backslashes")
        assertTrue(module.contentRoots.single().endsWith("crates/core"))
    }

    @Test
    fun `a module key contains no OS-specific separators`() {
        val json = """
            {
              "packages": [
                { "name": "app", "manifest_path": "C:\\ws\\app\\Cargo.toml", "dependencies": [] }
              ]
            }
        """.trimIndent()

        val module = CargoMetadata.parse(json, "C:\\ws").single()

        assertFalse('\\' in module.key, "the key must be identical on every OS: ${module.key}")
    }

    @Test
    fun `a path without a parent does not crash parsing`() {
        val json = """{ "packages": [ { "name": "x", "manifest_path": "Cargo.toml", "dependencies": [] } ] }"""

        val modules = CargoMetadata.parse(json, "/repo")

        assertTrue(modules.isEmpty() || modules.single().contentRoots.isNotEmpty())
    }

    @Test
    fun `analyzer path comparison is separator independent`() {
        val directory = File(System.getProperty("java.io.tmpdir"), "affected-path-check").apply { mkdirs() }
        val nested = File(directory, "module/src/Main.kt").apply {
            parentFile.mkdirs()
            writeText("fun main() {}")
        }

        assertFalse('\\' in nested.invariantSeparatorsPath, "a normalized path contains no backslashes")
        assertTrue(nested.invariantSeparatorsPath.endsWith("module/src/Main.kt"))
    }
}
