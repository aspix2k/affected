package com.aspix2k.affected.build

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeProcessRunnerTest {

    @Test
    fun `timeout terminates the parent and every descendant before returning`() {
        OwnedSandbox.use("affected-runner-tree") { sandbox ->
            val pids = sandbox.directory("pids")
            val held = sandbox.file("held.lock")

            val result = NativeProcessRunner.run(
                treeFixtureCommand(depth = 2, pids = pids, held = held),
                sandbox.root,
                timeoutSeconds = TREE_TIMEOUT_SECONDS,
            )

            assertFalse(result.completed, result.output)
            assertNull(result.exitCode)
            val published = (0..2).map { depth -> File(pids, "pid-$depth") }
            published.forEach { assertTrue(it.isFile, "Fixture process was not published: $it\n${result.output}") }
            assertEquals(result.pid, File(pids, "pid-2").readText().trim().toLong())
            published.forEach { file ->
                val handle = ProcessHandle.of(file.readText().trim().toLong()).orElse(null)
                assertFalse(handle?.isAlive ?: false, "Process from $file is still alive")
            }
            Files.delete(held.toPath())
        }
    }

    @Test
    fun `completed process reports exit code and output`() {
        OwnedSandbox.use("affected-runner-exit") { sandbox ->
            val passed = NativeProcessRunner.run(
                listOf(javaPath(), "-version"),
                sandbox.root,
                COMPLETE_TIMEOUT_SECONDS,
            )
            assertTrue(passed.completed)
            assertTrue(passed.passed, passed.output)
            assertContains(passed.output, "version")

            val failed = NativeProcessRunner.run(
                listOf(javaPath(), "-unknown-option"),
                sandbox.root,
                COMPLETE_TIMEOUT_SECONDS,
            )
            assertTrue(failed.completed)
            assertFalse(failed.passed)
            assertTrue((failed.exitCode ?: 0) != 0)
        }
    }

    @Test
    fun `execute fails a timed out command after terminating its tree`() {
        OwnedSandbox.use("affected-runner-execute") { sandbox ->
            val pids = sandbox.directory("pids")
            val failure = assertFailsWith<AssertionError> {
                NativeProcessRunner.execute(
                    treeFixtureCommand(depth = 1, pids = pids, held = sandbox.file("held.lock")),
                    sandbox.root,
                    timeoutSeconds = TREE_TIMEOUT_SECONDS,
                )
            }
            assertContains(failure.message.orEmpty(), "Timed out")
            (0..1).forEach { depth ->
                val handle = ProcessHandle.of(File(pids, "pid-$depth").readText().trim().toLong()).orElse(null)
                assertFalse(handle?.isAlive ?: false)
            }
        }
    }

    private companion object {
        const val TREE_TIMEOUT_SECONDS = 8L
        const val COMPLETE_TIMEOUT_SECONDS = 60L
    }
}

internal fun treeFixtureCommand(depth: Int, pids: File, held: File): List<String> = listOf(
    javaPath(),
    "-cp",
    System.getProperty("java.class.path"),
    TreeFixture::class.java.name,
    depth.toString(),
    pids.path,
    held.path,
)

internal object TreeFixture {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val depth = arguments[0].toInt()
        val pids = File(arguments[1])
        if (depth == 0) {
            FileOutputStream(arguments[2]).use {
                File(pids, "pid-0").writeText(ProcessHandle.current().pid().toString())
                Thread.sleep(TimeUnit.MINUTES.toMillis(5))
            }
        } else {
            File(pids, "pid-$depth").writeText(ProcessHandle.current().pid().toString())
            ProcessBuilder(treeFixtureCommand(depth - 1, pids, File(arguments[2])))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor()
        }
    }
}

private fun javaPath(): String = File(
    System.getProperty("java.home"),
    if (System.getProperty("os.name").startsWith("Windows")) "bin/java.exe" else "bin/java",
).path
