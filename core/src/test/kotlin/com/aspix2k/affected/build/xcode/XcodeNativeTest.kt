package com.aspix2k.affected.build.xcode

import com.aspix2k.affected.build.NativeProcessRunner
import com.aspix2k.affected.build.OwnedSandbox
import com.aspix2k.affected.build.process.CommandRunner
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class XcodeNativeTest {

    @Test
    fun `Xcode runs proven tests and builds schemes without runnable tests`() {
        assumeTrue(System.getProperty("affected.cliConformance") == "true")
        assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        assumeTrue(File("/usr/bin/xcodebuild").canExecute())
        val source = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "conformance/cli-fixtures/xcode") }
            .firstOrNull(File::isDirectory)
            ?: error("Missing public Xcode fixture")
        OwnedSandbox.use("affected-xcode-native") { sandbox ->
            val fixture = sandbox.root
            assertTrue(source.copyRecursively(fixture, overwrite = true))
            assertEquals(
                66,
                exitCode(fixture, listOf("xcodebuild", "test", "-scheme", "App")),
            )
            val marker = File(fixture, "affected-xcode-test.marker")
            val testCommand = xcodeCommands(fixture, listOf(".:test")).single()
            assertEquals(listOf("xcodebuild", "test", "-scheme", "AppTests"), testCommand.arguments)
            assertNotNull(
                CommandRunner.capture(
                    fixture.absolutePath,
                    testCommand.arguments,
                    timeoutSeconds = 120,
                ),
            )
            assertTrue(marker.isFile)

            assertTrue(File(fixture, "App.xcodeproj/xcshareddata/xcschemes/AppTests.xcscheme").delete())
            val command = xcodeCommands(fixture, listOf(".:test")).single()
            assertEquals(
                listOf("xcodebuild", "build", "-scheme", "App", "CODE_SIGNING_ALLOWED=NO"),
                command.arguments,
            )

            assertNotNull(
                CommandRunner.capture(
                    fixture.absolutePath,
                    command.arguments,
                    timeoutSeconds = 120,
                ),
            )
        }
    }

    private fun exitCode(root: File, arguments: List<String>): Int {
        val result = NativeProcessRunner.run(arguments, root, XCODEBUILD_TIMEOUT_SECONDS)
        check(result.completed) { "xcodebuild timed out" }
        return checkNotNull(result.exitCode)
    }

    private companion object {
        const val XCODEBUILD_TIMEOUT_SECONDS = 120L
    }
}
