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

    @Test
    fun `a pub workspace lists explicit packages with their own content roots`() {
        val root = dartWorkspace()
        val modules = requireNotNull(dartModules(root))

        assertEquals(listOf("packages/alpha", "packages/beta"), modules.map(BuildModule::executionId))
        assertEquals(listOf(true, false), modules.map(BuildModule::hasTests))
        assertEquals(File(root, "packages/alpha").invariantSeparatorsPath, modules[0].contentRoots.single())
        assertEquals(
            listOf("dart", "test", "packages/alpha/test"),
            dartCommands(modules.filter(BuildModule::hasTests).map { "${it.executionId}:test" })
                .single()
                .arguments,
        )
    }

    @Test
    fun `a glob in the workspace list keeps the root command`() {
        val root = dartRoot(
            """
            name: probe
            environment:
              sdk: ^3.13.0
            workspace:
              - packages/*
            """.trimIndent(),
        )
        File(root, "packages/alpha/pubspec.yaml").apply {
            parentFile.mkdirs()
            writeText("name: alpha\nenvironment:\n  sdk: ^3.13.0\n")
        }

        val modules = requireNotNull(dartModules(root))
        assertEquals(listOf("."), modules.map(BuildModule::executionId))
        assertTrue(modules.single().hasTests)
        assertEquals(listOf("dart", "test"), dartCommands(listOf(".:test")).single().arguments)
    }

    @Test
    fun `a missing workspace member keeps the root command`() {
        val root = dartRoot(
            """
            name: probe
            environment:
              sdk: ^3.13.0
            workspace:
              - packages/alpha
            """.trimIndent(),
        )

        val discovery = failClosedModules(root, "test", "analyze", dartModules(root))
        assertEquals(listOf("."), discovery.modules.map(BuildModule::executionId))
    }

    private fun dartWorkspace(): File {
        val root = dartRoot(
            """
            name: probe
            environment:
              sdk: ^3.13.0
            workspace:
              - packages/alpha
              - packages/beta
            """.trimIndent(),
        )
        File(root, "packages/alpha/pubspec.yaml").apply {
            parentFile.mkdirs()
            writeText("name: alpha\nresolution: workspace\nenvironment:\n  sdk: ^3.13.0\n")
        }
        File(root, "packages/alpha/test/alpha_test.dart").apply {
            parentFile.mkdirs()
            writeText("void main() {}")
        }
        File(root, "packages/beta/pubspec.yaml").apply {
            parentFile.mkdirs()
            writeText("name: beta\nresolution: workspace\nenvironment:\n  sdk: ^3.13.0\n")
        }
        return root
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
