package com.aspix2k.affected.build.process

import com.aspix2k.affected.awaitBounded
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CommandSequenceTest {

    @Test
    fun `a successful sequence runs every command in order and terminates with zero`() {
        val directory = createTempDirectory("sequence-success")
        val listener = RecordingListener()
        val sequence = sequence(
            directory,
            listOf(
                CliCommand("first", probe(directory, "print", "alpha")),
                CliCommand("second", probe(directory, "print", "beta")),
            ),
            listener,
        )

        sequence.start()

        assertEquals(0, listener.awaitExit())
        assertEquals(
            listOf("started first", "finished first 0", "started second", "finished second 0", "terminated 0"),
            listener.lifecycle(),
        )
        assertEquals(listOf("alpha\n", "beta\n"), listener.text(OutputKind.STDOUT))
        val system = listener.text(OutputKind.SYSTEM).joinToString("")
        assertTrue(system.indexOf("> first") in 0 until system.indexOf("> second"), system)
        assertFalse(sequence.isActive)
        assertSequenceThreadsStopped()
    }

    @Test
    fun `a failing command ends the sequence with its exit code`() {
        val directory = createTempDirectory("sequence-failure")
        val later = directory.resolve("later")
        val listener = RecordingListener()
        val sequence = sequence(
            directory,
            listOf(
                CliCommand("first", probe(directory, "exit", "0")),
                CliCommand("failure", probe(directory, "exit", "7")),
                CliCommand("never", probe(directory, "marker", later.toString())),
            ),
            listener,
        )

        sequence.start()

        assertEquals(7, listener.awaitExit())
        assertEquals(
            listOf("started first", "finished first 0", "started failure", "finished failure 7", "terminated 7"),
            listener.lifecycle(),
        )
        assertFalse(Files.exists(later))
    }

    @Test
    fun `a command that continues on failure lets the remainder run and keeps the first failure`() {
        val directory = createTempDirectory("sequence-command-continue")
        val listener = RecordingListener()
        val sequence = sequence(
            directory,
            listOf(
                CliCommand("failure", probe(directory, "exit", "3"), continueOnFailure = true),
                CliCommand("remainder", probe(directory, "exit", "0")),
                CliCommand("second failure", probe(directory, "exit", "5"), continueOnFailure = true),
                CliCommand("tail", probe(directory, "exit", "0")),
            ),
            listener,
        )

        sequence.start()

        assertEquals(3, listener.awaitExit())
        assertEquals(
            listOf(
                "started failure",
                "finished failure 3",
                "started remainder",
                "finished remainder 0",
                "started second failure",
                "finished second failure 5",
                "started tail",
                "finished tail 0",
                "terminated 3",
            ),
            listener.lifecycle(),
        )
    }

    @Test
    fun `a plan level continue runs every command and a failure without it does not`() {
        val directory = createTempDirectory("sequence-plan-continue")
        val continuing = RecordingListener()
        sequence(
            directory,
            listOf(
                CliCommand("failure", probe(directory, "exit", "2")),
                CliCommand("remainder", probe(directory, "exit", "0")),
            ),
            continuing,
            continueAfterFailure = true,
        ).start()
        val stopping = RecordingListener()
        sequence(
            directory,
            listOf(
                CliCommand("failure", probe(directory, "exit", "2")),
                CliCommand("remainder", probe(directory, "exit", "0")),
            ),
            stopping,
        ).start()

        assertEquals(2, continuing.awaitExit())
        assertEquals(2, stopping.awaitExit())
        assertTrue(continuing.lifecycle().contains("started remainder"))
        assertFalse(stopping.lifecycle().contains("started remainder"))
    }

    @Test
    fun `stopping a running command terminates its whole process tree and ends the sequence`() {
        val directory = createTempDirectory("sequence-stop-tree")
        val ready = directory.resolve("tree.ready")
        val childPid = directory.resolve("child.pid")
        val later = directory.resolve("later")
        val temporary = Files.createTempDirectory("affected-sequence-stop-")
        val listener = RecordingListener()
        val sequence = sequence(
            directory,
            listOf(
                CliCommand(
                    "tree",
                    probe(
                        directory,
                        "tree",
                        ready.toString(),
                        childPid.toString(),
                        testJavaClassPathArgument(directory),
                    ),
                    ownedTemporaryDirectories = listOf(temporary),
                ),
                CliCommand("never", probe(directory, "marker", later.toString())),
            ),
            listener,
        )
        var child: ProcessHandle? = null

        try {
            sequence.start()
            awaitFile(ready)
            child = ProcessHandle.of(Files.readString(childPid).trim().toLong()).orElseThrow()
            assertTrue(child.isAlive)

            assertTrue(sequence.requestStop())

            assertNotEquals(0, listener.awaitExit())
            assertFalse(child.isAlive, "Stop left a descendant of the command alive")
            assertFalse(Files.exists(later), "The command after Stop was started")
            assertFalse(Files.exists(temporary), "The owned temporary directory survived Stop")
            assertFalse(listener.lifecycle().contains("started never"))
            assertFalse(sequence.requestStop(), "A second stop must report that nothing was active")
            assertSequenceThreadsStopped()
        } finally {
            child?.takeIf(ProcessHandle::isAlive)?.destroyForcibly()
            temporary.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a deferred command is created after the previous command finished`() {
        val directory = createTempDirectory("sequence-deferred")
        val listener = RecordingListener()
        val firstFinished = AtomicBoolean()
        val resolvedAfterFirst = AtomicBoolean()
        val steps = listOf(
            CliCommand("first", probe(directory, "exit", "0")),
            DeferredCliCommand("empty") { null },
            DeferredCliCommand.command {
                resolvedAfterFirst.set(firstFinished.get())
                CliCommand("deferred", probe(directory, "print", "late"))
            },
        )
        val recording = object : CommandSequenceListener by listener {
            override fun onCommandFinished(command: CliCommand, exitCode: Int) {
                if (command.title == "first") firstFinished.set(true)
                listener.onCommandFinished(command, exitCode)
            }
        }

        sequence(directory, steps, recording).start()

        assertEquals(0, listener.awaitExit())
        assertTrue(resolvedAfterFirst.get(), "The deferred command was created before the first finished")
        assertEquals(
            listOf("started first", "finished first 0", "started deferred", "finished deferred 0", "terminated 0"),
            listener.lifecycle(),
        )
        assertEquals(listOf("late\n"), listener.text(OutputKind.STDOUT))
    }

    @Test
    fun `an empty sequence and a failed resolution fail visibly`() {
        val directory = createTempDirectory("sequence-unresolved")
        val empty = RecordingListener()
        val broken = RecordingListener()

        sequence(directory, emptyList(), empty).start()
        sequence(directory, listOf(DeferredCliCommand("resolve") { error("no SDK") }), broken).start()

        assertEquals(1, empty.awaitExit())
        assertTrue(empty.text(OutputKind.STDERR).joinToString("").contains("could not resolve the planned modules"))
        assertEquals(1, broken.awaitExit())
        assertTrue(broken.text(OutputKind.STDERR).joinToString("").contains("no SDK"))
    }

    @Test
    fun `large interleaved stdout and stderr arrive complete and in order per stream`() {
        val directory = createTempDirectory("sequence-interleaved")
        val listener = RecordingListener()
        val lines = 20_000

        sequence(
            directory,
            listOf(CliCommand("interleaved", probe(directory, "interleave", lines.toString()))),
            listener,
        ).start()

        assertEquals(0, listener.awaitExit())
        assertEquals(List(lines) { "out-$it\n" }.joinToString(""), listener.text(OutputKind.STDOUT).joinToString(""))
        assertEquals(List(lines) { "err-$it\n" }.joinToString(""), listener.text(OutputKind.STDERR).joinToString(""))
    }

    @Test
    fun `a stop before start terminates without launching anything`() {
        val directory = createTempDirectory("sequence-stop-before-start")
        val later = directory.resolve("later")
        val temporary = Files.createTempDirectory("affected-sequence-before-start-")
        val listener = RecordingListener()
        val sequence = sequence(
            directory,
            listOf(
                CliCommand(
                    "never",
                    probe(directory, "marker", later.toString()),
                    ownedTemporaryDirectories = listOf(temporary),
                ),
            ),
            listener,
        )

        assertTrue(sequence.requestStop())
        sequence.start()

        assertEquals(1, listener.awaitExit())
        assertFalse(Files.exists(later))
        assertFalse(Files.exists(temporary))
        assertEquals(listOf("terminated 1"), listener.lifecycle())
    }

    @Test
    fun `stopping a command reports cleanup that has to wait`() {
        val directory = createTempDirectory("sequence-stop-cleanup")
        val ready = directory.resolve("sleeper.ready")
        val temporary = Files.createTempDirectory("affected-sequence-cleanup-")
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val listener = RecordingListener()
        val sequence = sequence(
            directory,
            listOf(
                CliCommand(
                    "sleeper",
                    probe(directory, "sleep", ready.toString()),
                    ownedTemporaryDirectories = listOf(temporary),
                ),
            ),
            listener,
            hooks = SequenceHooks(
                ownedTemporaryDirectoryCleanup = {
                    cleanupStarted.countDown()
                    releaseCleanup.awaitBounded()
                    it.toFile().deleteRecursively()
                },
            ),
        )

        try {
            sequence.start()
            awaitFile(ready)
            assertTrue(sequence.requestStop())
            assertTrue(cleanupStarted.await(10, TimeUnit.SECONDS))

            assertFalse(listener.exit.isDone, "The sequence terminated before owned cleanup finished")
            releaseCleanup.countDown()
            assertNotEquals(0, listener.awaitExit())
            assertFalse(Files.exists(temporary))
        } finally {
            releaseCleanup.countDown()
            temporary.toFile().deleteRecursively()
        }
    }

    private fun sequence(
        directory: Path,
        steps: List<CliStep>,
        listener: CommandSequenceListener,
        continueAfterFailure: Boolean = false,
        hooks: SequenceHooks = SequenceHooks(),
    ): CommandSequence = CommandSequence(
        directory.toFile(),
        steps,
        listener,
        SupervisorTestHost.host,
        continueAfterFailure = continueAfterFailure,
        hooks = hooks,
    )

    private fun probe(directory: Path, vararg arguments: String): List<String> = listOf(
        File(System.getProperty("java.home"), if (isWindows()) "bin/java.exe" else "bin/java").absolutePath,
        testJavaClassPathArgument(directory),
        SequenceProbe::class.java.name,
    ) + arguments

    private fun awaitFile(file: Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!Files.isRegularFile(file) && System.nanoTime() < deadline) Thread.sleep(25)
        assertTrue(Files.isRegularFile(file), "The command did not publish $file")
    }

    private fun assertSequenceThreadsStopped() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        fun alive() = Thread.getAllStackTraces().keys.count {
            it.isAlive && it.name.startsWith("affected-command-sequence")
        }
        while (alive() > 0 && System.nanoTime() < deadline) Thread.sleep(25)
        assertEquals(0, alive(), "The sequence executor was not shut down")
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")
}

private class RecordingListener : CommandSequenceListener {
    val exit = CompletableFuture<Int>()
    private val events = CopyOnWriteArrayList<String>()
    private val output = ConcurrentLinkedQueue<Pair<OutputKind, String>>()

    override fun onText(text: String, kind: OutputKind) {
        output += kind to text
    }

    override fun onCommandStarted(command: CliCommand) {
        events += "started ${command.title}"
    }

    override fun onCommandFinished(command: CliCommand, exitCode: Int) {
        events += "finished ${command.title} $exitCode"
    }

    override fun onTerminated(exitCode: Int) {
        events += "terminated $exitCode"
        exit.complete(exitCode)
    }

    fun awaitExit(): Int = exit.get(60, TimeUnit.SECONDS)

    fun lifecycle(): List<String> = events.toList()

    fun text(kind: OutputKind): List<String> = output.filter { it.first == kind }.map { it.second }
}

private object SequenceProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        when (arguments[0]) {
            "print" -> print(arguments[1] + "\n")
            "exit" -> System.exit(arguments[1].toInt())
            "marker" -> Files.writeString(Path.of(arguments[1]), "started")
            "sleep" -> {
                Files.writeString(Path.of(arguments[1]), "ready")
                Thread.sleep(60_000)
            }
            "tree" -> tree(Path.of(arguments[1]), Path.of(arguments[2]), arguments[3])
            "interleave" -> interleave(arguments[1].toInt())
            else -> error("Unknown mode ${arguments[0]}")
        }
    }

    private fun tree(ready: Path, childPid: Path, classPathArgument: String) {
        val java = ProcessHandle.current().info().command().orElseThrow()
        val child = ProcessBuilder(java, classPathArgument, SequenceSleeper::class.java.name)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        Files.writeString(childPid, child.pid().toString())
        Files.writeString(ready, "ready")
        child.waitFor()
    }

    private fun interleave(lines: Int) {
        for (index in 0 until lines) {
            System.out.print("out-$index\n")
            System.err.print("err-$index\n")
        }
        System.out.flush()
        System.err.flush()
    }
}

private object SequenceSleeper {
    @JvmStatic
    fun main(arguments: Array<String>) {
        check(arguments.isEmpty())
        Thread.sleep(60_000)
    }
}
