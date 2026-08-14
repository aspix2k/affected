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
}
