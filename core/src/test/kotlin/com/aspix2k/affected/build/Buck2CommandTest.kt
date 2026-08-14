package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Buck2CommandTest {

    @Test
    fun `a Buck2 root runs one project test command`() {
        assertEquals(
            listOf("buck2", "test"),
            buck2Commands(listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a production-only Buck2 change builds the project`() {
        assertEquals(
            listOf("buck2", "build"),
            buck2Commands(listOf(".:build")).single().arguments,
        )
    }

    @Test
    fun `unknown Buck2 tasks keep the project test command`() {
        assertEquals(
            listOf("buck2", "test"),
            buck2Commands(listOf(".:mystery")).single().arguments,
        )
    }

    @Test
    fun `a Buck2 root is runnable`() {
        val root = buck2Root()
        val module = buck2RootModule(root)

        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals("build", module.compileTask)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `a static cell directory is a content root`() {
        val root = buck2Root("[cells]\n  extra = extra\n")
        File(root, "extra").mkdirs()
        val module = buck2RootModule(root)

        assertTrue(module.hasTests)
        assertEquals(
            listOf(root.invariantSeparatorsPath, File(root, "extra").invariantSeparatorsPath),
            module.contentRoots,
        )
    }

    @Test
    fun `an unproved cell keeps the project content root`() {
        val root = buck2Root("[cells]\n  extra = \${cell}\n")
        File(root, "extra").mkdirs()
        val module = buck2RootModule(root)

        assertTrue(module.hasTests)
        assertEquals(listOf(root.invariantSeparatorsPath), module.contentRoots)
    }

    @Test
    fun `a missing cell directory keeps the project content root`() {
        val root = buck2Root("[cells]\n  extra = missing\n")
        val module = buck2RootModule(root)

        assertTrue(module.hasTests)
        assertEquals(listOf(root.invariantSeparatorsPath), module.contentRoots)
    }

    @Test
    fun `a BUCK file without buckconfig stays off the Buck2 adapter`() {
        val root = createTempDirectory("buck1-root").toFile()
        File(root, "BUCK").writeText("export_file(name = \"readme\")\n")

        assertNull(buck2Manifest(root))
    }

    @Test
    fun `Gradle settings keep the root off the Buck2 adapter`() {
        val root = buck2Root()
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mixed\"")

        assertNull(buck2Manifest(root))
    }

    @Test
    fun `a Maven pom keeps the root off the Buck2 adapter`() {
        val root = buck2Root()
        File(root, "pom.xml").writeText("<project/>")

        assertNull(buck2Manifest(root))
    }

    private fun buck2Root(config: String = "[cells]\n"): File {
        val root = createTempDirectory("buck2-root").toFile()
        File(root, ".buckconfig").writeText(config)
        return root
    }
}
