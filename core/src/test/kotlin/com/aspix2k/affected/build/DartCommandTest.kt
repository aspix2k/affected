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
    fun `a dart_tool change keeps the workspace command`() {
        val root = dartWorkspace()

        assertTrue(
            dartRequiresWorkspace(
                root.path,
                BuildChanges(listOf(File(root, ".dart_tool/package_config.json").path), emptySet(), false),
            ),
        )
    }

    @Test
    fun `a generated file in a package stays scoped`() {
        val root = dartWorkspace()

        assertFalse(
            dartRequiresWorkspace(
                root.path,
                BuildChanges(listOf(File(root, "packages/alpha/lib/alpha.g.dart").path), emptySet(), false),
            ),
        )
    }

    @Test
    fun `an asset in a package stays scoped`() {
        val root = dartWorkspace()

        assertFalse(
            dartRequiresWorkspace(
                root.path,
                BuildChanges(listOf(File(root, "packages/alpha/assets/logo.png").path), emptySet(), false),
            ),
        )
    }

    @Test
    fun `a generated file at the workspace root keeps the workspace command`() {
        val root = dartWorkspace()

        assertTrue(
            dartRequiresWorkspace(
                root.path,
                BuildChanges(listOf(File(root, "lib/app.g.dart").path), emptySet(), false),
            ),
        )
    }

    @Test
    fun `a build_runner dependency runs generate before dart test`() {
        val root = dartRoot(
            """
            name: probe
            environment:
              sdk: ^3.13.0
            dev_dependencies:
              build_runner: ^2.4.0
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                listOf("dart", "run", "build_runner", "build", "--delete-conflicting-outputs"),
                listOf("dart", "test"),
            ),
            dartCommands(root, listOf(".:test")).map(CliCommand::arguments),
        )
    }

    @Test
    fun `a build yaml runs generate before dart analyze`() {
        val root = dartRoot()
        File(root, "build.yaml").writeText("targets:\n  \$default:\n    sources: []\n")

        assertEquals(
            listOf(
                listOf("dart", "run", "build_runner", "build", "--delete-conflicting-outputs"),
                listOf("dart", "analyze"),
            ),
            dartCommands(root, listOf(".:analyze")).map(CliCommand::arguments),
        )
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

    @Test
    fun `a single first-level nested Dart package is the root`() {
        val base = createTempDirectory("dart-nested").toFile()
        val nested = File(base, "pkg")
        dartRoot().copyRecursively(nested)

        assertEquals(nested.canonicalFile, dartProjectRoot(base)?.canonicalFile)
    }

    @Test
    fun `a Dart marker on the project base wins`() {
        val base = dartRoot()
        dartRoot().copyRecursively(File(base, "pkg"))

        assertEquals(base.canonicalFile, dartProjectRoot(base)?.canonicalFile)
    }

    @Test
    fun `several first-level nested Dart packages stay off`() {
        val base = createTempDirectory("dart-many").toFile()
        dartRoot().copyRecursively(File(base, "pkg"))
        dartRoot().copyRecursively(File(base, "cli"))

        assertNull(dartProjectRoot(base))
    }

    @Test
    fun `a deeper nested Dart package stays off`() {
        val base = createTempDirectory("dart-deep").toFile()
        dartRoot().copyRecursively(File(base, "src/pkg"))

        assertNull(dartProjectRoot(base))
    }

    @Test
    fun `a nested Flutter app is not a Dart root`() {
        val base = createTempDirectory("dart-flutter").toFile()
        val nested = File(base, "app")
        nested.mkdirs()
        File(nested, "pubspec.yaml").writeText(
            """
            name: probe
            environment:
              sdk: ^3.13.0
            dependencies:
              flutter:
                sdk: flutter
            """.trimIndent(),
        )

        assertNull(dartProjectRoot(base))
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
