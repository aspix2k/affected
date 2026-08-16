package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
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
            listOf("xcodebuild", "build", "CODE_SIGNING_ALLOWED=NO"),
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
    fun `a single user scheme is selected`() {
        val root = xcodeRoot()
        val schemes = File(root, "App.xcodeproj/xcuserdata/aspix.xcuserdatad/xcschemes")
        schemes.mkdirs()
        File(schemes, "iosApp.xcscheme").writeText(testableScheme())

        assertEquals(
            listOf("xcodebuild", "test", "-scheme", "iosApp"),
            xcodeCommands(root, listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a single shared scheme is selected`() {
        val root = xcodeRoot()
        val schemes = File(root, "App.xcodeproj/xcshareddata/xcschemes")
        schemes.mkdirs()
        File(schemes, "App.xcscheme").writeText(testableScheme())

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
        File(schemes, "App.xcscheme").writeText(testableScheme())
        File(schemes, "AppTests.xcscheme").writeText(testableScheme())

        assertEquals(
            listOf("xcodebuild", "test"),
            xcodeCommands(root, listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `Xcode scheme actions choose test or signing independent build commands`() {
        val root = xcodeRoot()
        val schemes = File(root, "App.xcodeproj/xcuserdata/developer.xcuserdatad/xcschemes")
        schemes.mkdirs()
        File(schemes, "iosApp.xcscheme").writeText(
            "<Scheme><BuildAction/><LaunchAction/></Scheme>",
        )

        val module = xcodeRootModule(root)

        assertTrue(module.hasTests)
        assertEquals(false, xcodeHasTests(root))
        assertEquals(
            listOf("xcodebuild", "build", "-scheme", "iosApp", "CODE_SIGNING_ALLOWED=NO"),
            xcodeCommands(root, listOf(".:test")).single().arguments,
        )

        val testRoot = xcodeRoot()
        val testSchemes = File(testRoot, "App.xcodeproj/xcshareddata/xcschemes").apply(File::mkdirs)
        File(testSchemes, "App.xcscheme").writeText(testableScheme())

        assertTrue(xcodeRootModule(testRoot).hasTests)
        assertTrue(xcodeHasTests(testRoot))
        assertEquals(
            listOf("xcodebuild", "test", "-scheme", "App"),
            xcodeCommands(testRoot, listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `one proven test scheme is selected among build only schemes`() {
        val root = xcodeRoot()
        val schemes = File(root, "App.xcodeproj/xcshareddata/xcschemes")
        schemes.mkdirs()
        File(schemes, "App.xcscheme").writeText("<Scheme><BuildAction/></Scheme>")
        File(schemes, "AppTests.xcscheme").writeText(testableScheme())

        assertEquals(
            listOf("xcodebuild", "test", "-scheme", "AppTests"),
            xcodeCommands(root, listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `conflicting definitions of one scheme keep test execution fail closed`() {
        val root = xcodeRoot()
        val shared = File(root, "App.xcodeproj/xcshareddata/xcschemes").apply(File::mkdirs)
        val user = File(root, "App.xcodeproj/xcuserdata/developer.xcuserdatad/xcschemes").apply(File::mkdirs)
        File(shared, "App.xcscheme").writeText(testableScheme())
        File(user, "App.xcscheme").writeText("<Scheme><BuildAction/></Scheme>")

        assertTrue(xcodeRootModule(root).hasTests)
        assertEquals(
            listOf("xcodebuild", "test"),
            xcodeCommands(root, listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `malformed scheme metadata keeps test execution fail closed`() {
        val root = xcodeRoot()
        val schemes = File(root, "App.xcodeproj/xcshareddata/xcschemes")
        schemes.mkdirs()
        File(schemes, "App.xcscheme").writeText("<Scheme><TestAction>")

        assertTrue(xcodeRootModule(root).hasTests)
        assertEquals(
            listOf("xcodebuild", "test"),
            xcodeCommands(root, listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `an empty test action is built instead of returning Xcode exit 66`() {
        val root = xcodeRoot()
        val schemes = File(root, "App.xcodeproj/xcshareddata/xcschemes").apply(File::mkdirs)
        File(schemes, "App.xcscheme").writeText(
            "<Scheme><BuildAction/><TestAction><Testables/></TestAction></Scheme>",
        )

        assertTrue(xcodeRootModule(root).hasTests)
        assertEquals(false, xcodeHasTests(root))
        assertEquals(
            listOf("xcodebuild", "build", "-scheme", "App", "CODE_SIGNING_ALLOWED=NO"),
            xcodeCommands(root, listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `execution reclassifies scheme metadata changed after planning`() {
        val root = xcodeRoot()
        val schemes = File(root, "App.xcodeproj/xcshareddata/xcschemes").apply(File::mkdirs)
        val scheme = File(schemes, "App.xcscheme").apply {
            writeText("<Scheme><BuildAction/></Scheme>")
        }
        val step = xcodeExecutionCommands(root, listOf(".:test")).single()

        assertEquals(
            listOf("xcodebuild", "build", "-scheme", "App", "CODE_SIGNING_ALLOWED=NO"),
            xcodeCommands(root, listOf(".:test")).single().arguments,
        )

        scheme.writeText(testableScheme())

        assertEquals(
            listOf("xcodebuild", "test", "-scheme", "App"),
            step.resolve()?.arguments,
        )
    }

    @Test
    fun `a test action containing only skipped tests is built`() {
        val root = xcodeRoot()
        val schemes = File(root, "App.xcodeproj/xcshareddata/xcschemes").apply(File::mkdirs)
        File(schemes, "App.xcscheme").writeText(
            "<Scheme><TestAction><Testables><TestableReference skipped=\"YES\"/>" +
                "</Testables></TestAction></Scheme>",
        )

        assertTrue(xcodeRootModule(root).hasTests)
        assertEquals(false, xcodeHasTests(root))
        assertEquals(
            listOf("xcodebuild", "build", "-scheme", "App", "CODE_SIGNING_ALLOWED=NO"),
            xcodeCommands(root, listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a test plan configures the scheme test action`() {
        val root = xcodeRoot()
        val schemes = File(root, "App.xcodeproj/xcshareddata/xcschemes").apply(File::mkdirs)
        File(schemes, "App.xcscheme").writeText(
            "<Scheme><TestAction><TestPlans><TestPlanReference reference=\"container:App.xctestplan\"/>" +
                "</TestPlans></TestAction></Scheme>",
        )

        assertTrue(xcodeRootModule(root).hasTests)
        assertEquals(
            listOf("xcodebuild", "test", "-scheme", "App"),
            xcodeCommands(root, listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `scheme metadata with a document type keeps test execution fail closed`() {
        val root = xcodeRoot()
        val schemes = File(root, "App.xcodeproj/xcshareddata/xcschemes")
        schemes.mkdirs()
        File(schemes, "App.xcscheme").writeText(
            "<!DOCTYPE Scheme [<!ENTITY external SYSTEM 'file:///etc/passwd'>]>" +
                "<Scheme><BuildAction>&external;</BuildAction></Scheme>",
        )

        assertTrue(xcodeRootModule(root).hasTests)
        assertEquals(
            listOf("xcodebuild", "test"),
            xcodeCommands(root, listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `oversized scheme metadata keeps test execution fail closed`() {
        val root = xcodeRoot()
        val schemes = File(root, "App.xcodeproj/xcshareddata/xcschemes").apply(File::mkdirs)
        File(schemes, "App.xcscheme").writeText(
            "<Scheme>" + " ".repeat(PerformanceBudgets.MAX_MANIFEST_BYTES.toInt()) + "</Scheme>",
        )

        assertTrue(xcodeRootModule(root).hasTests)
        assertEquals(
            listOf("xcodebuild", "test"),
            xcodeCommands(root, listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `scheme metadata behind an intermediate symlink keeps test execution fail closed`() {
        val root = xcodeRoot()
        val external = createTempDirectory("xcode-external-schemes").toFile()
        File(external, "App.xcscheme").writeText("<Scheme><BuildAction/></Scheme>")
        val shared = File(root, "App.xcodeproj/xcshareddata").apply(File::mkdirs)
        val linked = File(shared, "xcschemes").toPath()
        assumeTrue(runCatching { Files.createSymbolicLink(linked, external.toPath()) }.isSuccess)

        assertTrue(xcodeRootModule(root).hasTests)
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
    fun `a symlinked Xcode bundle does not activate the adapter`() {
        val root = createTempDirectory("xcode-symlink-root").toFile()
        val external = createTempDirectory("xcode-symlink-external").toFile()
        assumeTrue(
            runCatching {
                Files.createSymbolicLink(File(root, "App.xcodeproj").toPath(), external.toPath())
            }.isSuccess,
        )

        assertNull(xcodeManifest(root))
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
        assertEquals("validate", module.testTask)
        assertEquals("build", module.compileTask)
        assertEquals(".", module.executionId)
    }

    private fun xcodeRoot(): File {
        val root = createTempDirectory("xcode-root").toFile()
        File(root, "App.xcodeproj").mkdirs()
        return root
    }

    private fun testableScheme(): String =
        "<Scheme><TestAction><Testables><TestableReference skipped=\"NO\"/>" +
            "</Testables></TestAction></Scheme>"
}
