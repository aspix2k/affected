package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwiftCommandTest {

    @Test
    fun `a Swift package runs one project test command`() {
        assertEquals(
            listOf("swift", "test"),
            swiftCommands(listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a production-only Swift change builds the package`() {
        assertEquals(
            listOf("swift", "build"),
            swiftCommands(listOf(".:build")).single().arguments,
        )
    }

    @Test
    fun `unknown Swift tasks keep the project test command`() {
        assertEquals(
            listOf("swift", "test"),
            swiftCommands(listOf(".:mystery")).single().arguments,
        )
    }

    @Test
    fun `a named target with an unknown task keeps the project test command`() {
        assertEquals(
            listOf(listOf("swift", "test")),
            swiftCommands(listOf("Alpha:mystery")).map { it.arguments },
        )
        assertEquals(
            listOf(listOf("swift", "build", "--target", "Alpha"), listOf("swift", "test")),
            swiftCommands(listOf("Alpha:build", "AlphaTests:test", "Beta:mystery")).map { it.arguments },
        )
    }

    @Test
    fun `a failed describe is cached for the same manifests`() {
        val root = swiftRoot()
        var calls = 0
        val system = SwiftBuildSystem { calls++; null }

        assertEquals(".", system.modules(root).single().executionId)
        assertEquals(".", system.modules(root).single().executionId)
        assertEquals(1, calls)
    }

    @Test
    fun `a manifest written by describe does not invalidate the cached targets`() {
        val root = swiftRoot()
        var calls = 0
        val system = SwiftBuildSystem {
            calls++
            File(root, "Package.resolved").writeText("{}")
            DESCRIBE
        }

        assertEquals(listOf("Alpha", "AlphaTests"), system.modules(root).map { it.id })
        assertEquals(listOf("Alpha", "AlphaTests"), system.modules(root).map { it.id })
        assertEquals(1, calls)
    }

    @Test
    fun `a versioned manifest change refreshes the cached targets`() {
        val root = swiftRoot()
        val versioned = File(root, "Package@swift-5.9.swift").apply { writeText("// one") }
        var calls = 0
        val system = SwiftBuildSystem { calls++; DESCRIBE }

        system.modules(root)
        system.modules(root)
        versioned.writeText("// two")
        system.modules(root)

        assertEquals(2, calls)
    }

    @Test
    fun `Swift test targets run by anchored target filters after the built targets`() {
        val commands = swiftCommands(listOf("Alpha:build", "AlphaTests:test", "BetaTests:test", "AlphaTests:test"))

        assertEquals(
            listOf(
                listOf("swift", "build", "--target", "Alpha"),
                listOf("swift", "test", "--filter", "^AlphaTests\\.", "--filter", "^BetaTests\\."),
            ),
            commands.map { it.arguments },
        )
    }

    @Test
    fun `a whole package or unsafe target name keeps the project command`() {
        assertEquals(
            listOf(listOf("swift", "test")),
            swiftCommands(listOf("AlphaTests:test", ".:test")).map { it.arguments },
        )
        assertEquals(
            listOf(listOf("swift", "test")),
            swiftCommands(listOf("Alpha.*:test")).map { it.arguments },
        )
    }

    @Test
    fun `swift package describe output becomes target modules`() {
        val modules = checkNotNull(SwiftTargets.parse(DESCRIBE, File("/work/pkg")))

        assertEquals(listOf("Alpha", "AlphaTests"), modules.map { it.id })
        assertEquals(listOf(false, true), modules.map { it.hasTests })
        assertEquals(listOf("/work/pkg/Sources/Alpha"), modules.first().contentRoots)
        assertEquals(setOf("/work/pkg|Alpha"), modules.last().dependencies)
        assertEquals("/work/pkg", modules.first().root)
    }

    @Test
    fun `undescribed Swift targets keep the project command`() {
        val root = File("/work/pkg")

        assertNull(SwiftTargets.parse("not json", root))
        assertNull(SwiftTargets.parse("""{"targets": []}""", root))
        assertNull(SwiftTargets.parse(DESCRIBE.replace("library", "plugin").replace("test", "snippet"), root))
        assertNull(SwiftTargets.parse(DESCRIBE.replace("\"c99name\":\"Alpha\"", "\"c99name\":\"_Alpha\""), root))
        assertNull(SwiftTargets.parse(DESCRIBE.replace("\"name\":\"AlphaTests\"", "\"name\":\"Alpha\""), root))
    }

    @Test
    fun `snippet, plugin, macro, system, binary and unknown targets do not discard the package`() {
        val description = """
            {"targets":[
              {"c99name":"Alpha","name":"Alpha","path":"Sources/Alpha","type":"library"},
              {"c99name":"AlphaTests","name":"AlphaTests","path":"Tests/AlphaTests","type":"test",
               "target_dependencies":["Alpha"]},
              {"c99name":"basic_usage","name":"basic-usage","path":"Snippets","type":"snippet",
               "target_dependencies":["Alpha"]},
              {"c99name":"Gen","name":"Gen","path":"Plugins/Gen","type":"plugin"},
              {"c99name":"Sys","name":"Sys","path":"Sources/Sys","type":"system"},
              {"c99name":"Bin","name":"Bin","path":"Bin.xcframework","type":"binary"},
              {"c99name":"Future","name":"Future","path":"Sources/Future","type":"quantum"},
              {"c99name":"Macros","name":"Macros","path":"Sources/Macros","type":"macro",
               "target_dependencies":["Gen"]}
            ]}
        """.trimIndent()

        val modules = checkNotNull(SwiftTargets.parse(description, File("/work/pkg")))

        assertEquals(listOf("Alpha", "AlphaTests", "Macros"), modules.map { it.id })
        assertEquals(setOf("/work/pkg|Alpha"), modules[1].dependencies)
        assertEquals(emptySet(), modules[2].dependencies)
    }

    @Test
    fun `a package without code targets keeps the project command`() {
        val description = """{"targets":[{"c99name":"s","name":"s","path":"Snippets","type":"snippet"}]}"""
        val system = SwiftBuildSystem { description }

        assertNull(SwiftTargets.parse(description, File("/work/pkg")))
        assertEquals(".", system.modules(swiftRoot()).single().executionId)
    }

    @Test
    fun `a Swift package with tests is runnable`() {
        val root = swiftRoot()
        File(root, "Tests/ProbeTests/ProbeTests.swift").apply {
            parentFile.mkdirs()
            writeText("import XCTest")
        }
        val module = swiftRootModule(root)

        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals("build", module.compileTask)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `a Swift package without tests is built`() {
        val root = swiftRoot()

        assertFalse(swiftRootModule(root).hasTests)
    }

    @Test
    fun `an Xcode project without Package swift stays off the SwiftPM adapter`() {
        val root = createTempDirectory("xcode-root").toFile()
        File(root, "App.xcodeproj").mkdirs()

        assertNull(swiftManifest(root))
    }

    @Test
    fun `Gradle settings keep the root off the Swift adapter`() {
        val root = swiftRoot()
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mixed\"")

        assertNull(swiftManifest(root))
    }

    @Test
    fun `a Maven pom keeps the root off the Swift adapter`() {
        val root = swiftRoot()
        File(root, "pom.xml").writeText("<project/>")

        assertNull(swiftManifest(root))
    }

    private fun swiftRoot(): File {
        val root = createTempDirectory("swift-root").toFile()
        File(root, "Package.swift").writeText("let package = Package(name: \"probe\")\n")
        return root
    }

    private companion object {
        val DESCRIBE = """
            {"targets":[
              {"c99name":"Alpha","name":"Alpha","path":"Sources/Alpha","type":"library"},
              {"c99name":"AlphaTests","name":"AlphaTests","path":"Tests/AlphaTests","type":"test",
               "target_dependencies":["Alpha"]}
            ]}
        """.trimIndent()
    }
}
