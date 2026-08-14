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
    fun `a build_runner dependency runs generate before flutter test`() {
        val root = flutterRoot(
            """
            name: probe
            environment:
              sdk: ^3.13.0
            dependencies:
              flutter:
                sdk: flutter
            dev_dependencies:
              build_runner: ^2.4.0
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                listOf("dart", "run", "build_runner", "build", "--delete-conflicting-outputs"),
                listOf("flutter", "test"),
            ),
            flutterCommands(root, listOf(".:test")).map(CliCommand::arguments),
        )
    }

    @Test
    fun `a Maven pom keeps the root off the Flutter adapter`() {
        val root = flutterRoot()
        File(root, "pom.xml").writeText("<project/>")

        assertNull(flutterManifest(root))
    }

    @Test
    fun `a single first-level nested Flutter app is the root`() {
        val base = createTempDirectory("flutter-nested").toFile()
        val nested = File(base, "app")
        flutterRoot().copyRecursively(nested)

        assertEquals(nested.canonicalFile, flutterProjectRoot(base)?.canonicalFile)
    }

    @Test
    fun `a Flutter marker on the project base wins`() {
        val base = flutterRoot()
        flutterRoot().copyRecursively(File(base, "app"))

        assertEquals(base.canonicalFile, flutterProjectRoot(base)?.canonicalFile)
    }

    @Test
    fun `several first-level nested Flutter apps stay off`() {
        val base = createTempDirectory("flutter-many").toFile()
        flutterRoot().copyRecursively(File(base, "app"))
        flutterRoot().copyRecursively(File(base, "admin"))

        assertNull(flutterProjectRoot(base))
    }

    @Test
    fun `a deeper nested Flutter app stays off`() {
        val base = createTempDirectory("flutter-deep").toFile()
        flutterRoot().copyRecursively(File(base, "src/app"))

        assertNull(flutterProjectRoot(base))
    }

    @Test
    fun `a nested Dart package is not a Flutter root`() {
        val base = createTempDirectory("flutter-dart").toFile()
        val nested = File(base, "pkg")
        nested.mkdirs()
        File(nested, "pubspec.yaml").writeText(
            """
            name: probe
            environment:
              sdk: ^3.13.0
            """.trimIndent(),
        )

        assertNull(flutterProjectRoot(base))
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
