package com.aspix2k.affected.build

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    @Test
    fun `interruption terminates the parent and every descendant and restores the interrupt flag`() {
        OwnedSandbox.use("affected-runner-interrupt") { sandbox ->
            val pids = sandbox.directory("pids")
            val held = sandbox.file("held.lock")
            val thrown = AtomicReference<Throwable?>()
            val interruptRestored = AtomicReference<Boolean?>()
            val worker = Thread {
                try {
                    NativeProcessRunner.run(
                        treeFixtureCommand(depth = 2, pids = pids, held = held),
                        sandbox.root,
                        timeoutSeconds = COMPLETE_TIMEOUT_SECONDS,
                    )
                } catch (failure: Throwable) {
                    thrown.set(failure)
                } finally {
                    interruptRestored.set(Thread.currentThread().isInterrupted)
                }
            }
            worker.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COMPLETE_TIMEOUT_SECONDS)
            while (System.nanoTime() < deadline && !(0..2).all { depth -> published(pids, depth) != null }) {
                Thread.sleep(POLL_MILLIS)
            }
            val processes = (0..2).map { depth -> checkNotNull(published(pids, depth)) { "pid-$depth not published" } }
            assertTrue(processes.all { ProcessHandle.of(it).orElse(null)?.isAlive ?: false })

            worker.interrupt()
            worker.join(TimeUnit.SECONDS.toMillis(COMPLETE_TIMEOUT_SECONDS))

            assertFalse(worker.isAlive)
            assertIs<InterruptedException>(thrown.get())
            assertEquals(true, interruptRestored.get())
            assertTrue(thrown.get()?.suppressed.orEmpty().isEmpty(), thrown.get().toString())
            processes.forEach { pid ->
                assertFalse(ProcessHandle.of(pid).orElse(null)?.isAlive ?: false, "Process $pid is still alive")
            }
            Files.delete(held.toPath())
        }
    }

    @Test
    fun `output is bounded by bytes and reports truncation`() {
        OwnedSandbox.use("affected-runner-output") { sandbox ->
            val truncated = NativeProcessRunner.run(
                listOf(javaPath(), "-version"),
                sandbox.root,
                COMPLETE_TIMEOUT_SECONDS,
                outputLimitBytes = OUTPUT_LIMIT_BYTES,
            )
            assertTrue(truncated.passed)
            assertTrue(truncated.truncated)
            assertEquals(OUTPUT_LIMIT_BYTES, truncated.output.toByteArray().size)

            val whole = NativeProcessRunner.run(listOf(javaPath(), "-version"), sandbox.root, COMPLETE_TIMEOUT_SECONDS)
            assertFalse(whole.truncated)
            assertTrue(whole.output.length > OUTPUT_LIMIT_BYTES)
        }
    }

    private fun published(pids: File, depth: Int): Long? =
        File(pids, "pid-$depth").takeIf(File::isFile)?.readText()?.trim()?.toLongOrNull()

    private companion object {
        const val TREE_TIMEOUT_SECONDS = 8L
        const val COMPLETE_TIMEOUT_SECONDS = 60L
        const val POLL_MILLIS = 50L
        const val OUTPUT_LIMIT_BYTES = 8
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
