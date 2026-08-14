package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlutterCommandTest {

    @Test
    fun `a Flutter root runs one project test command`() {
        assertEquals(
            listOf("flutter", "test"),
            flutterCommands(listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a production-only Flutter change analyzes the project`() {
        assertEquals(
            listOf("flutter", "analyze"),
            flutterCommands(listOf(".:analyze")).single().arguments,
        )
    }

    @Test
    fun `unknown Flutter tasks keep the project test command`() {
        assertEquals(
            listOf("flutter", "test"),
            flutterCommands(listOf(".:mystery")).single().arguments,
        )
    }

    @Test
    fun `a Flutter package with tests is runnable`() {
        val root = flutterRoot()
        File(root, "test/alpha_test.dart").apply {
            parentFile.mkdirs()
            writeText("void main() {}")
        }

        val module = flutterRootModule(root)
        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals("analyze", module.compileTask)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `a Flutter package without tests is analyzed`() {
        val root = flutterRoot()

        assertFalse(flutterRootModule(root).hasTests)
    }

    @Test
    fun `a Dart package without Flutter stays off the Flutter adapter`() {
        val root = flutterRoot(
            """
            name: probe
            environment:
              sdk: ^3.13.0
            """.trimIndent(),
        )

        assertNull(flutterManifest(root))
        assertNotNull(dartManifest(root))
    }

    @Test
    fun `a nested Android Gradle folder keeps the Flutter root`() {
        val root = flutterRoot()
        File(root, "android").mkdirs()
        File(root, "android/settings.gradle").writeText("rootProject.name = \"app\"")

        assertNotNull(flutterManifest(root))
    }

    @Test
    fun `Gradle settings keep the root off the Flutter adapter`() {
        val root = flutterRoot()
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mixed\"")

        assertNull(flutterManifest(root))
    }

    @Test
    fun `a Maven pom keeps the root off the Flutter adapter`() {
        val root = flutterRoot()
        File(root, "pom.xml").writeText("<project/>")

        assertNull(flutterManifest(root))
    }

    private fun flutterRoot(
        pubspec: String = """
            name: probe
            environment:
              sdk: ^3.13.0
            dependencies:
              flutter:
                sdk: flutter
        """.trimIndent(),
    ): File {
        val root = createTempDirectory("flutter-root").toFile()
        File(root, "pubspec.yaml").writeText(pubspec)
        return root
    }
}
