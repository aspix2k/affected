package com.aspix2k.affected

import com.aspix2k.affected.build.CargoMetadata
import com.aspix2k.affected.build.PlannedExecutionRoot
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
                  "dependencies": [],
                  "targets": [{"kind":["lib"],"doctest":true}]
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
                {
                  "name": "app",
                  "manifest_path": "C:\\ws\\app\\Cargo.toml",
                  "dependencies": [],
                  "targets": [{"kind":["bin"],"doctest":false}]
                }
              ]
            }
        """.trimIndent()

        val module = CargoMetadata.parse(json, "C:\\ws").single()

        assertFalse('\\' in module.key, "the key must be identical on every OS: ${module.key}")
    }

    @Test
    fun `a path without a parent does not crash parsing`() {
        val json = """{
            "packages": [{
                "name": "x",
                "manifest_path": "Cargo.toml",
                "dependencies": [],
                "targets": [{"kind":["bin"],"doctest":false}]
            }]
        }"""

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

    @Test
    fun `spaces and non-ASCII names stay in the normalized path`() {
        val directory = File(System.getProperty("java.io.tmpdir"), "affected path проверка").apply { mkdirs() }
        val nested = File(directory, "модуль/src/Главный.kt").apply {
            parentFile.mkdirs()
            writeText("fun main() {}")
        }

        val normalized = nested.invariantSeparatorsPath
        assertFalse('\\' in normalized)
        assertTrue("affected path проверка" in normalized)
        assertTrue(normalized.endsWith("модуль/src/Главный.kt"))
    }

    @Test
    fun `execution root identity is stable for platform paths`() {
        val project = createTempDirectory("affected platform root")
        val root = project.resolve("-модуль проверка").createDirectory()
        val guard = PlannedExecutionRoot.capture(root).bind(project)
        val creationTime = Files.readAttributes(
            root,
            java.nio.file.attribute.BasicFileAttributes::class.java,
        ).creationTime()

        assertNull(guard.validationFailure())

        Files.delete(root)
        Files.createDirectory(root)
        if (System.getProperty("os.name").startsWith("Windows")) {
            Files.setAttribute(root, "basic:creationTime", creationTime)
        }
        assertNotNull(guard.validationFailure())
    }

    @Test
    fun `a Windows directory junction is not an execution root`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val project = createTempDirectory("affected-junction-project")
        val target = createTempDirectory("affected-junction-target")
        val junction = project.resolve("junction")
        val process = ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), target.toString())
            .redirectErrorStream(true)
            .start()
        assumeTrue(process.waitFor() == 0)
        try {
            assertNotNull(PlannedExecutionRoot.capture(junction).bind(project).validationFailure())
        } finally {
            Files.deleteIfExists(junction)
        }
    }
}
