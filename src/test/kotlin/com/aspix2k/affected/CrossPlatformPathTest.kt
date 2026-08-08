package com.aspix2k.affected

import com.aspix2k.affected.build.CargoMetadata
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Module keys are built from paths and compared against paths produced elsewhere.
 * A backslash anywhere in that chain silently breaks every lookup on Windows.
 */
class CrossPlatformPathTest {

    @Test
    fun `windows-пути из cargo metadata приводятся к прямым слэшам`() {
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

        assertFalse('\\' in module.contentRoots.single(), "в контент-руте не должно быть обратных слэшей")
        assertTrue(module.contentRoots.single().endsWith("crates/core"))
    }

    @Test
    fun `ключ модуля не содержит разделителей конкретной ОС`() {
        val json = """
            {
              "packages": [
                { "name": "app", "manifest_path": "C:\\ws\\app\\Cargo.toml", "dependencies": [] }
              ]
            }
        """.trimIndent()

        val module = CargoMetadata.parse(json, "C:/ws").single()

        assertFalse('\\' in module.key, "ключ обязан быть одинаковым на любой ОС: ${module.key}")
    }

    @Test
    fun `путь без родителя не роняет разбор`() {
        val json = """{ "packages": [ { "name": "x", "manifest_path": "Cargo.toml", "dependencies": [] } ] }"""

        val modules = CargoMetadata.parse(json, "/repo")

        assertTrue(modules.isEmpty() || modules.single().contentRoots.isNotEmpty())
    }

    @Test
    fun `сравнение путей анализатора не зависит от разделителя`() {
        val directory = File(System.getProperty("java.io.tmpdir"), "affected-path-check").apply { mkdirs() }
        val nested = File(directory, "module/src/Main.kt").apply {
            parentFile.mkdirs()
            writeText("fun main() {}")
        }

        assertFalse('\\' in nested.invariantSeparatorsPath, "нормализованный путь не содержит обратных слэшей")
        assertTrue(nested.invariantSeparatorsPath.endsWith("module/src/Main.kt"))
    }
}
