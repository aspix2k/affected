package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BazelCommandTest {

    @Test
    fun `a Bazel root runs one workspace test command`() {
        assertEquals(
            listOf("bazel", "test", "//..."),
            bazelCommands(listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a production-only Bazel change builds the workspace`() {
        assertEquals(
            listOf("bazel", "build", "//..."),
            bazelCommands(listOf(".:build")).single().arguments,
        )
    }

    @Test
    fun `unknown Bazel tasks keep the workspace test command`() {
        assertEquals(
            listOf("bazel", "test", "//..."),
            bazelCommands(listOf(".:mystery")).single().arguments,
        )
    }

    @Test
    fun `a MODULE bazel root with a test rule is runnable`() {
        val root = bazelRoot("MODULE.bazel")
        File(root, "BUILD.bazel").writeText("sh_test(name = \"alpha_test\", srcs = [\"alpha_test.sh\"])")

        val module = bazelRootModule(root)
        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals("build", module.compileTask)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `a Bazel workspace without test rules is built`() {
        val root = bazelRoot("MODULE.bazel")
        File(root, "BUILD.bazel").writeText("filegroup(name = \"src\")")

        assertFalse(bazelRootModule(root).hasTests)
    }

    @Test
    fun `Gradle settings keep the root off the Bazel adapter`() {
        val root = bazelRoot("MODULE.bazel")
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mixed\"")

        assertNull(bazelManifest(root))
    }

    @Test
    fun `a Maven pom keeps the root off the Bazel adapter`() {
        val root = bazelRoot("WORKSPACE")
        File(root, "pom.xml").writeText("<project/>")

        assertNull(bazelManifest(root))
    }

    private fun bazelRoot(marker: String): File {
        val root = createTempDirectory("bazel-root").toFile()
        File(root, marker).writeText("module(name = \"probe\")\n")
        return root
    }
}
