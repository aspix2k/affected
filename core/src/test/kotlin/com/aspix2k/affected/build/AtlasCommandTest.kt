package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtlasCommandTest {

    @Test
    fun `a local Atlas root runs one migrate validate command`() {
        assertEquals(
            listOf("atlas", "migrate", "validate"),
            atlasCommands(listOf(".:validate")).single().arguments,
        )
    }

    @Test
    fun `a production-only Atlas change still validates migrations`() {
        assertEquals(
            listOf("atlas", "migrate", "validate"),
            atlasCommands(listOf(".:compile")).single().arguments,
        )
    }

    @Test
    fun `unknown Atlas tasks keep migrate validate`() {
        assertEquals(
            listOf("atlas", "migrate", "validate"),
            atlasCommands(listOf(".:mystery")).single().arguments,
        )
    }

    @Test
    fun `a local Atlas root is runnable`() {
        val root = atlasRoot()
        val module = atlasRootModule(root)

        assertTrue(module.hasTests)
        assertEquals("validate", module.testTask)
        assertEquals("validate", module.compileTask)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `Gradle settings keep the root off the Atlas adapter`() {
        val root = atlasRoot()
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mixed\"")

        assertNull(atlasManifest(root))
    }

    @Test
    fun `a Maven pom keeps the root off the Atlas adapter`() {
        val root = atlasRoot()
        File(root, "pom.xml").writeText("<project/>")

        assertNull(atlasManifest(root))
    }

    @Test
    fun `a database URL stays off the adapter`() {
        val root = atlasRoot(
            """
            env "local" {
              url = "postgres://localhost/shop"
            }
            """.trimIndent(),
        )

        assertNull(atlasManifest(root))
    }

    @Test
    fun `a dev-url stays off the adapter`() {
        val root = atlasRoot(
            """
            env "local" {
              dev = "docker://postgres/16/dev"
            }
            """.trimIndent(),
        )

        assertNull(atlasManifest(root))
    }

    @Test
    fun `a cloud directory stays off the adapter`() {
        val root = atlasRoot("env \"ci\" {\n  dir = \"atlas://app\"\n}\n")

        assertNull(atlasManifest(root))
    }

    @Test
    fun `an unproved interpolation stays off the adapter`() {
        val root = atlasRoot("env \"local\" {\n  dir = \"file://${'$'}{MIGRATIONS}\"\n}\n")

        assertNull(atlasManifest(root))
    }

    @Test
    fun `an unreadable Atlas manifest stays off the adapter`() {
        val root = createTempDirectory("atlas-unreadable").toFile()
        val manifest = File(root, "atlas.hcl")
        manifest.writeText(LOCAL_CONFIG)
        check(manifest.setReadable(false))

        assertNull(atlasManifest(root))
        manifest.setReadable(true)
    }

    @Test
    fun `a lone SQL file stays off the adapter`() {
        val root = createTempDirectory("atlas-plain").toFile()
        File(root, "schema.sql").writeText("CREATE TABLE item (id INTEGER);")

        assertNull(atlasManifest(root))
    }

    private fun atlasRoot(config: String = LOCAL_CONFIG): File {
        val root = createTempDirectory("atlas-root").toFile()
        File(root, "atlas.hcl").writeText(config)
        return root
    }

    private companion object {
        const val LOCAL_CONFIG = """
variable "unused" {
  type = string
  default = "local"
}
"""
    }
}
