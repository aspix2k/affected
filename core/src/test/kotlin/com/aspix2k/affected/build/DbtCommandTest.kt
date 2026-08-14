package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DbtCommandTest {

    @Test
    fun `a local DuckDB dbt root runs one project test command`() {
        assertEquals(
            listOf("dbt", "test", "--project-dir", ".", "--profiles-dir", "."),
            dbtCommands(listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a production-only dbt change compiles the project`() {
        assertEquals(
            listOf("dbt", "compile", "--project-dir", ".", "--profiles-dir", "."),
            dbtCommands(listOf(".:compile")).single().arguments,
        )
    }

    @Test
    fun `unknown dbt tasks keep the project test command`() {
        assertEquals(
            listOf("dbt", "test", "--project-dir", ".", "--profiles-dir", "."),
            dbtCommands(listOf(".:mystery")).single().arguments,
        )
    }

    @Test
    fun `a local DuckDB dbt root is runnable`() {
        val root = dbtRoot()
        val module = dbtRootModule(root)

        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals("compile", module.compileTask)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `a dbt project without an in-repo profile stays off the adapter`() {
        val root = createTempDirectory("dbt-noprofile").toFile()
        File(root, "dbt_project.yml").writeText("name: shop\n")

        assertNull(dbtManifest(root))
    }

    @Test
    fun `a warehouse profile stays off the adapter`() {
        val root = dbtRoot(
            profiles = """
                shop:
                  target: dev
                  outputs:
                    dev:
                      type: postgres
                      host: localhost
            """.trimIndent(),
        )

        assertNull(dbtManifest(root))
    }

    @Test
    fun `an unproved profile interpolation stays off the adapter`() {
        val root = dbtRoot(
            profiles = """
                shop:
                  target: dev
                  outputs:
                    dev:
                      type: ${'$'}{adapter}
                      path: local.duckdb
            """.trimIndent(),
        )

        assertNull(dbtManifest(root))
    }

    @Test
    fun `a MotherDuck path stays off the adapter`() {
        val root = dbtRoot(
            profiles = """
                shop:
                  target: dev
                  outputs:
                    dev:
                      type: duckdb
                      path: md:cloud
            """.trimIndent(),
        )

        assertNull(dbtManifest(root))
    }

    @Test
    fun `Gradle settings keep the root off the dbt adapter`() {
        val root = dbtRoot()
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mixed\"")

        assertNull(dbtManifest(root))
    }

    @Test
    fun `a Maven pom keeps the root off the dbt adapter`() {
        val root = dbtRoot()
        File(root, "pom.xml").writeText("<project/>")

        assertNull(dbtManifest(root))
    }

    private fun dbtRoot(
        profiles: String = """
            shop:
              target: dev
              outputs:
                dev:
                  type: duckdb
                  path: local.duckdb
        """.trimIndent(),
    ): File {
        val root = createTempDirectory("dbt-root").toFile()
        File(root, "dbt_project.yml").writeText("name: shop\nprofile: shop\n")
        File(root, "profiles.yml").writeText(profiles)
        return root
    }
}
