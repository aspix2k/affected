package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MakeCommandTest {

    @Test
    fun `a Makefile with a test target runs make test`() {
        val root = makeRoot("test:\n\t@echo ok\n")

        assertEquals(
            listOf("make", "test"),
            makeCommands(root, listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a check target is used when test is absent`() {
        val root = makeRoot("check:\n\t@echo ok\n")
        val module = makeRootModule(root)

        assertTrue(module.hasTests)
        assertEquals("check", module.testTask)
        assertEquals(
            listOf("make", "check"),
            makeCommands(root, listOf(".:${module.testTask}")).single().arguments,
        )
    }

    @Test
    fun `a production-only Make change runs the default target`() {
        val root = makeRoot("all:\n\t@echo built\ntest:\n\t@echo ok\n")

        assertEquals(
            listOf("make"),
            makeCommands(root, listOf(".:all")).single().arguments,
        )
    }

    @Test
    fun `unknown Make tasks keep the test command`() {
        val root = makeRoot("test:\n\t@echo ok\n")

        assertEquals(
            listOf("make", "test"),
            makeCommands(root, listOf(".:mystery")).single().arguments,
        )
    }

    @Test
    fun `a Makefile without tests is compiled`() {
        val root = makeRoot("all:\n\t@echo built\n")
        val module = makeRootModule(root)

        assertFalse(module.hasTests)
        assertEquals("all", module.compileTask)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `Gradle settings keep the root off the Make adapter`() {
        val root = makeRoot("test:\n\t@echo ok\n")
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mixed\"")

        assertNull(makeManifest(root))
    }

    @Test
    fun `a CMake lists file keeps the root off the Make adapter`() {
        val root = makeRoot("test:\n\t@echo ok\n")
        File(root, "CMakeLists.txt").writeText("cmake_minimum_required(VERSION 3.29)")

        assertNull(makeManifest(root))
    }

    @Test
    fun `a Meson file keeps the root off the Make adapter`() {
        val root = makeRoot("test:\n\t@echo ok\n")
        File(root, "meson.build").writeText("project('probe', 'c')\n")

        assertNull(makeManifest(root))
    }

    @Test
    fun `a Maven pom keeps the root off the Make adapter`() {
        val root = makeRoot("test:\n\t@echo ok\n")
        File(root, "pom.xml").writeText("<project/>")

        assertNull(makeManifest(root))
    }

    @Test
    fun `a static include contributes its test target`() {
        val root = makeRoot("include testdefs.mk\nall:\n\t@echo built\n")
        File(root, "testdefs.mk").writeText("test:\n\t@echo ok\n")
        val module = makeRootModule(root)

        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals(
            listOf("make", "test"),
            makeCommands(root, listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `an unproved include keeps the test command`() {
        val root = makeRoot("include \$(wildcard *.mk)\nall:\n\t@echo built\n")
        val module = makeRootModule(root)

        assertTrue(module.hasTests)
        assertEquals(
            listOf("make", "test"),
            makeCommands(root, listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a missing include keeps the test command`() {
        val root = makeRoot("include missing.mk\nall:\n\t@echo built\n")
        val module = makeRootModule(root)

        assertTrue(module.hasTests)
        assertEquals(listOf("make", "test"), makeCommands(root, listOf(".:test")).single().arguments)
    }

    private fun makeRoot(manifest: String): File {
        val root = createTempDirectory("make-root").toFile()
        File(root, "Makefile").writeText(manifest)
        return root
    }
}
