package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DartCommandTest {

    @Test
    fun `a Dart root runs one project test command`() {
        assertEquals(
            listOf("dart", "test"),
            dartCommands(listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a production-only Dart change analyzes the project`() {
        assertEquals(
            listOf("dart", "analyze"),
            dartCommands(listOf(".:analyze")).single().arguments,
        )
    }

    @Test
    fun `unknown Dart tasks keep the project test command`() {
        assertEquals(
            listOf("dart", "test"),
            dartCommands(listOf(".:mystery")).single().arguments,
        )
    }

    @Test
    fun `a Dart package with tests is runnable`() {
        val root = dartRoot()
        File(root, "test/alpha_test.dart").apply {
            parentFile.mkdirs()
            writeText("void main() {}")
        }

        val module = dartRootModule(root)
        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals("analyze", module.compileTask)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `a Dart package without tests is analyzed`() {
        val root = dartRoot()

        assertFalse(dartRootModule(root).hasTests)
    }

    @Test
    fun `a Flutter SDK dependency keeps the root off the Dart adapter`() {
        val root = dartRoot(
            """
            name: mixed
            environment:
              sdk: ^3.13.0
            dependencies:
              flutter:
                sdk: flutter
            """.trimIndent(),
        )

        assertNull(dartManifest(root))
    }

    @Test
    fun `Gradle settings keep the root off the Dart adapter`() {
        val root = dartRoot()
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mixed\"")

        assertNull(dartManifest(root))
    }

    @Test
    fun `a Maven pom keeps the root off the Dart adapter`() {
        val root = dartRoot()
        File(root, "pom.xml").writeText("<project/>")

        assertNull(dartManifest(root))
    }

    private fun dartRoot(
        pubspec: String = """
            name: probe
            environment:
              sdk: ^3.13.0
        """.trimIndent(),
    ): File {
        val root = createTempDirectory("dart-root").toFile()
        File(root, "pubspec.yaml").writeText(pubspec)
        return root
    }
}
