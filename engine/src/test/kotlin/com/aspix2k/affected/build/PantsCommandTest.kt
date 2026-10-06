package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PantsCommandTest {

    @Test
    fun `a Pants root runs one project test command`() {
        assertEquals(
            listOf("pants", "test"),
            pantsCommands(listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a production-only Pants change checks the project`() {
        assertEquals(
            listOf("pants", "check"),
            pantsCommands(listOf(".:check")).single().arguments,
        )
    }

    @Test
    fun `unknown Pants tasks keep the project test command`() {
        assertEquals(
            listOf("pants", "test"),
            pantsCommands(listOf(".:mystery")).single().arguments,
        )
    }

    @Test
    fun `a Pants root is runnable`() {
        val root = pantsRoot()
        val module = pantsRootModule(root)

        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals("check", module.compileTask)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `Gradle settings keep the root off the Pants adapter`() {
        val root = pantsRoot()
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mixed\"")

        assertNull(pantsManifest(root))
    }

    @Test
    fun `a Maven pom keeps the root off the Pants adapter`() {
        val root = pantsRoot()
        File(root, "pom.xml").writeText("<project/>")

        assertNull(pantsManifest(root))
    }

    @Test
    fun `a single first-level nested Pants manifest is the root`() {
        val base = createTempDirectory("pants-nested").toFile()
        val nested = File(base, "backend")
        pantsRoot().copyRecursively(nested)

        assertEquals(nested.canonicalFile, pantsProjectRoot(base)?.canonicalFile)
    }

    @Test
    fun `several first-level nested Pants projects stay off`() {
        val base = createTempDirectory("pants-many").toFile()
        pantsRoot().copyRecursively(File(base, "backend"))
        pantsRoot().copyRecursively(File(base, "services"))

        assertNull(pantsProjectRoot(base))
    }

    private fun pantsRoot(): File {
        val root = createTempDirectory("pants-root").toFile()
        File(root, "pants.toml").writeText("[GLOBAL]\npants_version = \"2.27.0\"\n")
        return root
    }
}
