package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AntCommandTest {

    @Test
    fun `an Ant root runs one project test command`() {
        assertEquals(
            listOf("ant", "test"),
            antCommands(listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a production-only Ant change compiles the project`() {
        assertEquals(
            listOf("ant", "compile"),
            antCommands(listOf(".:compile")).single().arguments,
        )
    }

    @Test
    fun `unknown Ant tasks keep the project test command`() {
        assertEquals(
            listOf("ant", "test"),
            antCommands(listOf(".:mystery")).single().arguments,
        )
    }

    @Test
    fun `an Ant module with a test target is runnable`() {
        val root = antRoot("<project><target name=\"test\"/></project>")

        val module = antRootModule(root)
        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals("compile", module.compileTask)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `an Ant module without a test target is compiled`() {
        val root = antRoot("<project><target name=\"compile\"/></project>")

        val module = antRootModule(root)
        assertFalse(module.hasTests)
        assertEquals("compile", module.compileTask)
    }

    @Test
    fun `a junit target is treated as the Ant test task`() {
        val root = antRoot("<project><target name=\"junit\"/></project>")

        val module = antRootModule(root)
        assertTrue(module.hasTests)
        assertEquals("junit", module.testTask)
        assertEquals(
            listOf("ant", "junit"),
            antCommands(listOf(".:${module.testTask}")).single().arguments,
        )
    }

    @Test
    fun `Gradle settings keep the root off the Ant adapter`() {
        val root = antRoot("<project><target name=\"test\"/></project>")
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mixed\"")

        assertNull(antManifest(root))
    }

    @Test
    fun `a Maven pom keeps the root off the Ant adapter`() {
        val root = antRoot("<project><target name=\"test\"/></project>")
        File(root, "pom.xml").writeText("<project/>")

        assertNull(antManifest(root))
    }

    @Test
    fun `an imported file contributes its test target`() {
        val root = antRoot("<project><import file=\"testdefs.xml\"/></project>")
        File(root, "testdefs.xml").writeText("<project><target name=\"test\"/></project>")
        val module = antRootModule(root)

        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals(listOf("ant", "test"), antCommands(listOf(".:test")).single().arguments)
    }

    @Test
    fun `an unproved import keeps the test command`() {
        val root = antRoot("<project><import file=\"\${defs}\"/><target name=\"compile\"/></project>")
        val module = antRootModule(root)

        assertTrue(module.hasTests)
        assertEquals(listOf("ant", "test"), antCommands(listOf(".:test")).single().arguments)
    }

    @Test
    fun `a missing import keeps the test command`() {
        val root = antRoot("<project><import file=\"missing.xml\"/><target name=\"compile\"/></project>")
        val module = antRootModule(root)

        assertTrue(module.hasTests)
        assertEquals(listOf("ant", "test"), antCommands(listOf(".:test")).single().arguments)
    }

    @Test
    fun `an optional missing import does not invent tests`() {
        val root = antRoot(
            "<project><import file=\"missing.xml\" optional=\"true\"/><target name=\"compile\"/></project>",
        )
        val module = antRootModule(root)

        assertFalse(module.hasTests)
        assertEquals("compile", module.compileTask)
    }

    private fun antRoot(buildXml: String): File {
        val root = createTempDirectory("ant-root").toFile()
        File(root, "build.xml").writeText(buildXml)
        return root
    }
}
