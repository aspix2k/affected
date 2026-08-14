package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlcCommandTest {

    @Test
    fun `a local sqlc root runs one project compile command`() {
        assertEquals(
            listOf("sqlc", "compile"),
            sqlcCommands(listOf(".:compile")).single().arguments,
        )
    }

    @Test
    fun `a production-only sqlc change compiles the project`() {
        assertEquals(
            listOf("sqlc", "compile"),
            sqlcCommands(listOf(".:compile")).single().arguments,
        )
    }

    @Test
    fun `unknown sqlc tasks keep the project compile command`() {
        assertEquals(
            listOf("sqlc", "compile"),
            sqlcCommands(listOf(".:mystery")).single().arguments,
        )
    }

    @Test
    fun `a local sqlc root is runnable`() {
        val root = sqlcRoot()
        val module = sqlcRootModule(root)

        assertTrue(module.hasTests)
        assertEquals("compile", module.testTask)
        assertEquals("compile", module.compileTask)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `a yml sqlc manifest is a local root`() {
        val root = createTempDirectory("sqlc-yml").toFile()
        File(root, "sqlc.yml").writeText(LOCAL_CONFIG)

        assertEquals(File(root, "sqlc.yml"), sqlcManifest(root))
    }

    @Test
    fun `a json sqlc manifest is a local root`() {
        val root = createTempDirectory("sqlc-json").toFile()
        File(root, "sqlc.json").writeText(
            """{"version":"2","sql":[{"engine":"sqlite","schema":"schema.sql","queries":"query.sql"}]}""",
        )

        assertEquals(File(root, "sqlc.json"), sqlcManifest(root))
    }

    @Test
    fun `a lone SQL file stays off the adapter`() {
        val root = createTempDirectory("sqlc-plain").toFile()
        File(root, "query.sql").writeText("SELECT 1;")

        assertNull(sqlcManifest(root))
    }

    @Test
    fun `Gradle settings keep the root off the sqlc adapter`() {
        val root = sqlcRoot()
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mixed\"")

        assertNull(sqlcManifest(root))
    }

    @Test
    fun `a Maven pom keeps the root off the sqlc adapter`() {
        val root = sqlcRoot()
        File(root, "pom.xml").writeText("<project/>")

        assertNull(sqlcManifest(root))
    }

    @Test
    fun `a database URI stays off the adapter`() {
        val root = sqlcRoot(
            """
            version: "2"
            sql:
              - engine: postgresql
                schema: schema.sql
                queries: query.sql
                database:
                  uri: postgresql://localhost/shop
            """.trimIndent(),
        )

        assertNull(sqlcManifest(root))
    }

    @Test
    fun `a managed database stays off the adapter`() {
        val root = sqlcRoot(
            """
            version: "2"
            sql:
              - engine: postgresql
                schema: schema.sql
                queries: query.sql
                database:
                  managed: true
            """.trimIndent(),
        )

        assertNull(sqlcManifest(root))
    }

    @Test
    fun `a cloud project stays off the adapter`() {
        val root = sqlcRoot(
            """
            version: "2"
            cloud:
              project: abc
            sql:
              - engine: sqlite
                schema: schema.sql
                queries: query.sql
            """.trimIndent(),
        )

        assertNull(sqlcManifest(root))
    }

    @Test
    fun `an unproved interpolation stays off the adapter`() {
        val root = sqlcRoot(
            """
            version: "2"
            sql:
              - engine: sqlite
                schema: ${'$'}{schema}
                queries: query.sql
            """.trimIndent(),
        )

        assertNull(sqlcManifest(root))
    }

    @Test
    fun `an unreadable sqlc manifest stays off the adapter`() {
        val root = createTempDirectory("sqlc-unreadable").toFile()
        val manifest = File(root, "sqlc.yaml")
        manifest.writeText(LOCAL_CONFIG)
        check(manifest.setReadable(false))

        assertNull(sqlcManifest(root))
        manifest.setReadable(true)
    }

    private fun sqlcRoot(config: String = LOCAL_CONFIG): File {
        val root = createTempDirectory("sqlc-root").toFile()
        File(root, "sqlc.yaml").writeText(config)
        return root
    }

    private companion object {
        const val LOCAL_CONFIG = """
version: "2"
sql:
  - engine: sqlite
    schema: schema.sql
    queries: query.sql
"""
    }
}
