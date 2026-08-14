package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NinjaCommandTest {

    @Test
    fun `a build ninja with a test target runs ninja test`() {
        val root = ninjaRoot("build test: phony\n")

        assertEquals(
            listOf("ninja", "test"),
            ninjaCommands(root, listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a check target is used when test is absent`() {
        val root = ninjaRoot("build check: phony\n")
        val module = ninjaRootModule(root)

        assertTrue(module.hasTests)
        assertEquals("check", module.testTask)
        assertEquals(
            listOf("ninja", "check"),
            ninjaCommands(root, listOf(".:${module.testTask}")).single().arguments,
        )
    }

    @Test
    fun `a production-only Ninja change runs the default target`() {
        val root = ninjaRoot("build test: phony\nbuild all: phony\n")

        assertEquals(
            listOf("ninja"),
            ninjaCommands(root, listOf(".:default")).single().arguments,
        )
    }

    @Test
    fun `unknown Ninja tasks keep the test command`() {
        val root = ninjaRoot("build test: phony\n")

        assertEquals(
            listOf("ninja", "test"),
            ninjaCommands(root, listOf(".:mystery")).single().arguments,
        )
    }

    @Test
    fun `a Ninja file without tests is compiled`() {
        val root = ninjaRoot("build all: phony\n")
        val module = ninjaRootModule(root)

        assertFalse(module.hasTests)
        assertEquals("default", module.compileTask)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `Ninja edges are not treated as source ownership`() {
        val root = ninjaRoot("build test: exec src/alpha.c | generated.h\n")

        val module = ninjaRootModule(root)
        assertEquals(listOf(root.invariantSeparatorsPath), module.contentRoots)
        assertTrue(module.hasTests)
    }

    @Test
    fun `a Makefile keeps the root off the Ninja adapter`() {
        val root = ninjaRoot("build test: phony\n")
        File(root, "Makefile").writeText("test:\n\t@echo ok\n")

        assertNull(ninjaManifest(root))
    }

    @Test
    fun `a Meson file keeps the root off the Ninja adapter`() {
        val root = ninjaRoot("build test: phony\n")
        File(root, "meson.build").writeText("project('probe', 'c')\n")

        assertNull(ninjaManifest(root))
    }

    @Test
    fun `a CMake lists file keeps the root off the Ninja adapter`() {
        val root = ninjaRoot("build test: phony\n")
        File(root, "CMakeLists.txt").writeText("cmake_minimum_required(VERSION 3.29)")

        assertNull(ninjaManifest(root))
    }

    @Test
    fun `Gradle settings keep the root off the Ninja adapter`() {
        val root = ninjaRoot("build test: phony\n")
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mixed\"")

        assertNull(ninjaManifest(root))
    }

    @Test
    fun `a single first-level nested Ninja project is the root`() {
        val base = createTempDirectory("ninja-nested").toFile()
        val nested = File(base, "native")
        ninjaRoot("build test: phony\n").copyRecursively(nested)

        assertEquals(nested.canonicalFile, ninjaProjectRoot(base)?.canonicalFile)
    }

    @Test
    fun `several first-level nested Ninja projects stay off`() {
        val base = createTempDirectory("ninja-many").toFile()
        ninjaRoot("build test: phony\n").copyRecursively(File(base, "native"))
        ninjaRoot("build test: phony\n").copyRecursively(File(base, "tools"))

        assertNull(ninjaProjectRoot(base))
    }

    private fun ninjaRoot(manifest: String): File {
        val root = createTempDirectory("ninja-root").toFile()
        File(root, "build.ninja").writeText(manifest)
        return root
    }
}
