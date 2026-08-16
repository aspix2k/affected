package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
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
        val fixture = createTempDirectory("affected-xcode-native").toFile()
        try {
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
        } finally {
            assertTrue(fixture.deleteRecursively())
        }
    }

    private fun exitCode(root: File, arguments: List<String>): Int {
        val process = ProcessBuilder(arguments)
            .directory(root)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        var terminated = false
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS)) error("xcodebuild timed out")
            terminated = true
            return process.exitValue()
        } finally {
            if (!terminated) check(terminate(process))
        }
    }

    private fun terminate(process: Process): Boolean {
        var interrupted = Thread.interrupted()
        return try {
            val handles = process.toHandle().descendants().toList() + process.toHandle()
            handles.forEach(ProcessHandle::destroyForcibly)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (handles.any(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            handles.none(ProcessHandle::isAlive)
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
