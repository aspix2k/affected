package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SbtCommandTest {

    @Test
    fun `the sbt root runs one project test command`() {
        assertEquals(
            listOf("sbt", "--batch", "test"),
            sbtCommands(listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a production-only sbt change compiles the project`() {
        assertEquals(
            listOf("sbt", "--batch", "compile"),
            sbtCommands(listOf(".:compile")).single().arguments,
        )
    }

    @Test
    fun `unknown sbt tasks keep the project test command`() {
        assertEquals(
            listOf("sbt", "--batch", "test"),
            sbtCommands(listOf(".:mystery")).single().arguments,
        )
    }

    @Test
    fun `an sbt module with Scala tests is runnable`() {
        val root = createTempDirectory("sbt-tests").toFile()
        File(root, "src/test/scala/AlphaSpec.scala").apply {
            parentFile.mkdirs()
            writeText("class AlphaSpec")
        }

        val module = sbtRootModule(root)
        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals("compile", module.compileTask)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `an sbt module without tests is compiled`() {
        val root = createTempDirectory("sbt-main").toFile()
        File(root, "src/main/scala/Alpha.scala").apply {
            parentFile.mkdirs()
            writeText("class Alpha")
        }

        assertFalse(sbtRootModule(root).hasTests)
    }
}
