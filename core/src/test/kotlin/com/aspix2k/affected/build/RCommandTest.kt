package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RCommandTest {

    @Test
    fun `an R root runs one project test command`() {
        assertEquals(
            listOf("Rscript", "-e", "testthat::test_dir(\"tests/testthat\")"),
            rCommands(listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a production-only R change checks the DESCRIPTION`() {
        val root = rRoot()

        assertEquals(
            listOf("Rscript", "-e", "read.dcf(\"DESCRIPTION\")"),
            rCommands(root, listOf(".:check")).single().arguments,
        )
    }

    @Test
    fun `unknown R tasks keep the project test command`() {
        assertEquals(
            listOf("Rscript", "-e", "testthat::test_dir(\"tests/testthat\")"),
            rCommands(listOf(".:mystery")).single().arguments,
        )
    }

    @Test
    fun `an R package with tests is runnable`() {
        val root = rRoot()
        File(root, "tests/testthat/test-alpha.R").apply {
            parentFile.mkdirs()
            writeText("test_that(\"alpha\", { expect_true(TRUE) })\n")
        }

        val module = rRootModule(root)
        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals("check", module.compileTask)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `an R package without tests is checked`() {
        val root = rRoot()

        assertFalse(rRootModule(root).hasTests)
    }

    @Test
    fun `a lockfile-only R root keeps the test command`() {
        val root = createTempDirectory("r-renv").toFile()
        File(root, "renv.lock").writeText("{}\n")

        val module = rRootModule(root)
        assertTrue(module.hasTests)
        assertEquals(
            listOf("Rscript", "-e", "testthat::test_dir(\"tests/testthat\")"),
            rCommands(root, listOf(".:check")).single().arguments,
        )
    }

    @Test
    fun `a DESCRIPTION without a Package field stays off the R adapter`() {
        val root = rRoot("Title: not a package\n")

        assertNull(rManifest(root))
    }

    @Test
    fun `Gradle settings keep the root off the R adapter`() {
        val root = rRoot()
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mixed\"")

        assertNull(rManifest(root))
    }

    @Test
    fun `a Maven pom keeps the root off the R adapter`() {
        val root = rRoot()
        File(root, "pom.xml").writeText("<project/>")

        assertNull(rManifest(root))
    }

    private fun rRoot(
        description: String = """
            Package: probe
            Title: Probe
            Version: 0.0.1
            License: MIT
        """.trimIndent(),
    ): File {
        val root = createTempDirectory("r-root").toFile()
        File(root, "DESCRIPTION").writeText(description)
        return root
    }
}
