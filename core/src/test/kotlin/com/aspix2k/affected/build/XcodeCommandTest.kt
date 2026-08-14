package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XcodeCommandTest {

    @Test
    fun `an Xcode root runs one project test command`() {
        val root = xcodeRoot()

        assertEquals(
            listOf("xcodebuild", "test"),
            xcodeCommands(root, listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a production-only Xcode change builds the project`() {
        val root = xcodeRoot()

        assertEquals(
            listOf("xcodebuild", "build"),
            xcodeCommands(root, listOf(".:build")).single().arguments,
        )
    }

    @Test
    fun `unknown Xcode tasks keep the project test command`() {
        val root = xcodeRoot()

        assertEquals(
            listOf("xcodebuild", "test"),
            xcodeCommands(root, listOf(".:mystery")).single().arguments,
        )
    }

    @Test
    fun `a single shared scheme is selected`() {
        val root = xcodeRoot()
        val schemes = File(root, "App.xcodeproj/xcshareddata/xcschemes")
        schemes.mkdirs()
        File(schemes, "App.xcscheme").writeText("<Scheme/>")

        assertEquals(
            listOf("xcodebuild", "test", "-scheme", "App"),
            xcodeCommands(root, listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `several shared schemes keep the unscoped test command`() {
        val root = xcodeRoot()
        val schemes = File(root, "App.xcodeproj/xcshareddata/xcschemes")
        schemes.mkdirs()
        File(schemes, "App.xcscheme").writeText("<Scheme/>")
        File(schemes, "AppTests.xcscheme").writeText("<Scheme/>")

        assertEquals(
            listOf("xcodebuild", "test"),
            xcodeCommands(root, listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a Package swift root stays off the Xcode adapter`() {
        val root = xcodeRoot()
        File(root, "Package.swift").writeText("let package = Package(name: \"probe\")\n")

        assertNull(xcodeManifest(root))
        assertNotNull(swiftManifest(root))
    }

    @Test
    fun `Gradle settings keep the root off the Xcode adapter`() {
        val root = xcodeRoot()
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mixed\"")

        assertNull(xcodeManifest(root))
    }

    @Test
    fun `a Maven pom keeps the root off the Xcode adapter`() {
        val root = xcodeRoot()
        File(root, "pom.xml").writeText("<project/>")

        assertNull(xcodeManifest(root))
    }

    @Test
    fun `an Xcode root is runnable`() {
        val root = xcodeRoot()
        val module = xcodeRootModule(root)

        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals("build", module.compileTask)
        assertEquals(".", module.executionId)
    }

    private fun xcodeRoot(): File {
        val root = createTempDirectory("xcode-root").toFile()
        File(root, "App.xcodeproj").mkdirs()
        return root
    }
}
