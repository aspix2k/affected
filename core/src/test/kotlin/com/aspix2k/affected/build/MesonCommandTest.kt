package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MesonCommandTest {

    @Test
    fun `a Meson root sets up and runs one project test command`() {
        val root = mesonRoot()

        assertEquals(
            listOf(
                listOf("meson", "setup", "build"),
                listOf("meson", "test", "-C", "build"),
            ),
            mesonCommands(root, listOf(".:test")).map(CliCommand::arguments),
        )
    }

    @Test
    fun `a configured Meson build skips setup`() {
        val root = mesonRoot()
        File(root, "build/meson-info").mkdirs()

        assertEquals(
            listOf(listOf("meson", "test", "-C", "build")),
            mesonCommands(root, listOf(".:test")).map(CliCommand::arguments),
        )
    }

    @Test
    fun `a production-only Meson change compiles the project`() {
        val root = mesonRoot()

        assertEquals(
            listOf(
                listOf("meson", "setup", "build"),
                listOf("meson", "compile", "-C", "build"),
            ),
            mesonCommands(root, listOf(".:compile")).map(CliCommand::arguments),
        )
    }

    @Test
    fun `unknown Meson tasks keep the project test command`() {
        val root = mesonRoot()
        File(root, "build/meson-info").mkdirs()

        assertEquals(
            listOf(listOf("meson", "test", "-C", "build")),
            mesonCommands(root, listOf(".:mystery")).map(CliCommand::arguments),
        )
    }

    @Test
    fun `a Meson project with tests is runnable`() {
        val root = mesonRoot("project('probe', 'c')\ntest('alpha', executable('alpha', 'alpha.c'))\n")

        val module = mesonRootModule(root)
        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals("compile", module.compileTask)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `a Meson project without tests is compiled`() {
        val root = mesonRoot("project('probe', 'c')\nexecutable('app', 'app.c')\n")

        assertFalse(mesonRootModule(root).hasTests)
    }

    @Test
    fun `an occupied build directory uses builddir`() {
        val root = mesonRoot()
        File(root, "build").mkdirs()
        File(root, "build/CMakeCache.txt").writeText("cmake")

        assertEquals("builddir", mesonBuildDirectory(root))
        assertEquals(
            listOf(
                listOf("meson", "setup", "builddir"),
                listOf("meson", "test", "-C", "builddir"),
            ),
            mesonCommands(root, listOf(".:test")).map(CliCommand::arguments),
        )
    }

    @Test
    fun `Gradle settings keep the root off the Meson adapter`() {
        val root = mesonRoot()
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mixed\"")

        assertNull(mesonManifest(root))
    }

    @Test
    fun `a CMake lists file keeps the root off the Meson adapter`() {
        val root = mesonRoot()
        File(root, "CMakeLists.txt").writeText("cmake_minimum_required(VERSION 3.29)")

        assertNull(mesonManifest(root))
    }

    @Test
    fun `a Maven pom keeps the root off the Meson adapter`() {
        val root = mesonRoot()
        File(root, "pom.xml").writeText("<project/>")

        assertNull(mesonManifest(root))
    }

    @Test
    fun `a subproject test keeps the project runnable`() {
        val root = mesonRoot("project('probe', 'c')\nsubproject('alpha')\n")
        File(root, "subprojects/alpha").mkdirs()
        File(root, "subprojects/alpha/meson.build").writeText(
            "project('alpha', 'c')\ntest('alpha', executable('alpha', 'alpha.c'))\n",
        )
        val module = mesonRootModule(root)

        assertTrue(module.hasTests)
        assertTrue(module.contentRoots.any { it.endsWith("subprojects/alpha") })
        assertEquals(
            listOf(
                listOf("meson", "setup", "build"),
                listOf("meson", "test", "-C", "build"),
            ),
            mesonCommands(root, listOf(".:test")).map(CliCommand::arguments),
        )
    }

    @Test
    fun `a wrap-only subprojects directory does not invent tests`() {
        val root = mesonRoot("project('probe', 'c')\nexecutable('app', 'app.c')\n")
        File(root, "subprojects").mkdirs()
        File(root, "subprojects/alpha.wrap").writeText("[wrap-file]\ndirectory = alpha\n")
        File(root, "subprojects/packagecache/alpha").mkdirs()
        File(root, "subprojects/packagecache/alpha/meson.build").writeText(
            "project('cached', 'c')\ntest('cached', executable('cached', 'cached.c'))\n",
        )

        val module = mesonRootModule(root)
        assertFalse(module.hasTests)
        assertEquals(listOf(root.invariantSeparatorsPath), module.contentRoots)
    }

    @Test
    fun `meson-info tests keep the project runnable`() {
        val root = mesonRoot("project('probe', 'c')\nexecutable('app', 'app.c')\n")
        File(root, "subprojects/alpha").mkdirs()
        File(root, "subprojects/alpha/meson.build").writeText("project('alpha', 'c')\n")
        File(root, "build/meson-info").mkdirs()
        File(root, "build/meson-info/intro-projectinfo.json").writeText(
            """{"descriptive_name":"probe","subproject_dir":"subprojects","subprojects":["alpha"]}""",
        )
        File(root, "build/meson-info/intro-tests.json").writeText("""[{"name":"alpha","suite":["alpha"]}]""")

        val module = mesonRootModule(root)
        assertTrue(module.hasTests)
        assertTrue(module.contentRoots.any { it.endsWith("subprojects/alpha") })
    }

    @Test
    fun `unreadable meson-info keeps the test command`() {
        val root = mesonRoot("project('probe', 'c')\nexecutable('app', 'app.c')\n")
        File(root, "build/meson-info").mkdirs()
        File(root, "build/meson-info/intro-tests.json").writeText("{")

        val module = mesonRootModule(root)
        assertTrue(module.hasTests)
        assertEquals(
            listOf(listOf("meson", "test", "-C", "build")),
            mesonCommands(root, listOf(".:test")).map(CliCommand::arguments),
        )
    }

    private fun mesonRoot(manifest: String = "project('probe', 'c')\n"): File {
        val root = createTempDirectory("meson-root").toFile()
        File(root, "meson.build").writeText(manifest)
        return root
    }
}
