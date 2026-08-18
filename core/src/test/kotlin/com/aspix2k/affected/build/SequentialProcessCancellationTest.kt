package com.aspix2k.affected.build

import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SequentialProcessCancellationTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testDetachingACommandTerminatesItsOwnedProcessAndClosesItsTracker() {
        val directory = createTempDirectory("sequential-detached-tracker").toRealPath()
        var process: Process? = null
        var termination: ProcessTreeTermination? = null
        val output = StringBuilder()
        val handler = SequentialProcessHandler(
            directory.toFile(),
            listOf(CliCommand("detached tracker", listOf(java(), "-version"))),
            processFactory = { _, afterInitialPass ->
                val handler = sleeper().also { process = it }.let { OSProcessHandler(it, "detached tracker") }
                val tracker = ProcessTreeTermination(
                    handler.process.toHandle(),
                    afterInitialPass = afterInitialPass,
                ).also { termination = it }
                RunningCommand(handler, tracker, {})
            },
        )
        handler.addProcessListener(listener(output))

        try {
            handler.startNotify()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (termination == null && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(
                termination != null,
                "The process tracker was not created; terminated=${handler.isProcessTerminated}; output=$output",
            )

            handler.detachProcess()

            assertTrue(handler.waitFor(5_000))
            assertTrue(checkNotNull(termination).isClosed, "The detached process tracker remained scheduled")
            assertFalse(checkNotNull(process).isAlive, "The detached owned process remained alive")
        } finally {
            process?.takeIf(Process::isAlive)?.destroyForcibly()
            process?.waitFor(5, TimeUnit.SECONDS)
            directory.toFile().deleteRecursively()
        }
    }

    fun testStopIsRejectedAfterProcessTerminalDecisionWhileCleanupRemainsOwned() {
        val directory = createTempDirectory("sequential-terminal-decision")
        val marker = directory.resolve("started")
        val temporary = Files.createTempDirectory("affected-handler-terminal-decision-")
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val handler = SequentialProcessHandler(
            directory.toFile(),
            listOf(
                CliCommand(
                    "terminal decision",
                    markerCommand(marker),
                    ownedTemporaryDirectories = listOf(temporary),
                ),
            ),
            ownedTemporaryDirectoryCleanup = {
                cleanupStarted.countDown()
                releaseCleanup.await()
                it.toFile().deleteRecursively()
            },
        )

        try {
            handler.startNotify()
            assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS))
            assertTrue(Files.isRegularFile(marker))
            assertFalse(handler.stopIfActive())
            assertFalse(handler.waitFor(100))

            releaseCleanup.countDown()
            assertTrue(handler.waitFor(5_000))
            assertEquals(0, handler.exitCode)
            assertFalse(Files.exists(temporary))
        } finally {
            releaseCleanup.countDown()
            if (!handler.isProcessTerminated) handler.destroyProcess()
            temporary.toFile().deleteRecursively()
        }
    }

    fun testStoppingACommandTerminatesDescendantsBeforeOwnedCleanup() {
        val directory = createTempDirectory("sequential-process-tree")
        val source = directory.resolve("HandlerTree.java")
        val ready = directory.resolve("child.ready")
        val pid = directory.resolve("child.pid")
        val parentPid = directory.resolve("parent.pid")
        val temporary = Files.createTempDirectory("affected-handler-process-tree-")
        val initialTermination = CountDownLatch(1)
        val releaseTermination = CountDownLatch(1)
        if (isWindows()) {
            source.toFile().writeText(processTreeSource())
            val compiler = ProcessBuilder(javac(), source.toString()).directory(directory.toFile()).start()
            assertEquals(0, compiler.waitFor())
        }
        val handler = SequentialProcessHandler(
            directory.toFile(),
            listOf(
                CliCommand(
                    "process tree",
                    processTreeCommand(directory, temporary, pid, ready, parentPid),
                    ownedTemporaryDirectories = listOf(temporary),
                ),
            ),
            afterInitialProcessTermination = {
                initialTermination.countDown()
                releaseTermination.await()
            },
        )

        var destroying: Thread? = null
        try {
            handler.startNotify()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!ready.toFile().isFile && System.nanoTime() < deadline) Thread.sleep(25)
            assertTrue(ready.toFile().isFile)
            val childPid = pid.toFile().readText().toLong()
            val parentProcess = ProcessHandle.of(parentPid.toFile().readText().toLong()).orElseThrow()
            destroying = Thread(handler::destroyProcess).apply { start() }
            assertTrue(initialTermination.await(5, TimeUnit.SECONDS))
            val parentDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (parentProcess.isAlive && System.nanoTime() < parentDeadline) Thread.sleep(25)
            assertFalse(parentProcess.isAlive)
            assertFalse(handler.waitFor(100))
            assertTrue(Files.isDirectory(temporary))
            releaseTermination.countDown()
            destroying.join(5_000)
            assertFalse(destroying.isAlive)
            assertTrue(handler.waitFor(10_000))
            assertTrue(handler.exitCode != 0)
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
            assertFalse(Files.exists(temporary))
        } finally {
            releaseTermination.countDown()
            destroying?.join(5_000)
            if (!handler.isProcessTerminated) handler.destroyProcess()
            if (pid.toFile().isFile) {
                ProcessHandle.of(pid.toFile().readText().toLong()).ifPresent { it.destroyForcibly() }
            }
            temporary.toFile().deleteRecursively()
        }
    }

    fun testStopAfterRootExitTerminatesAChildThatEscapedBeforeTracking() {
        val directory = createTempDirectory("sequential-pretracking-child")
        val pid = directory.resolve("child.pid")
        val rootPid = directory.resolve("root.pid")
        val temporary = Files.createTempDirectory("affected-handler-pretracking-child-")
        val targetExited = CountDownLatch(1)
        val releaseTargetExit = CountDownLatch(1)
        val cleanupSawLivingChild = AtomicBoolean()
        var child: ProcessHandle? = null
        val unrelated = sleeper()
        val handler = SequentialProcessHandler(
            directory.toFile(),
            listOf(
                CliCommand(
                    "pre-tracking child",
                    escapedChildCommand(pid, rootPid = rootPid),
                    ownedTemporaryDirectories = listOf(temporary),
                ),
            ),
            processFactory = { commandLine, _ ->
                val contained = ContainedProcess.prepare(commandLine, afterTargetExit = {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (child == null && System.nanoTime() < deadline) {
                        child = runCatching {
                            ProcessHandle.of(Files.readString(pid).trim().toLong()).orElse(null)
                        }.getOrNull()
                        if (child == null) Thread.sleep(10)
                    }
                    checkNotNull(child)
                    check(child?.isAlive == true)
                    targetExited.countDown()
                    releaseTargetExit.await()
                })
                val processHandler = OSProcessHandler(
                    contained.process,
                    commandLine.commandLineString,
                    commandLine.charset,
                ).apply { setShouldDestroyProcessRecursively(false) }
                RunningCommand(processHandler, contained, contained::start)
            },
            ownedTemporaryDirectoryCleanup = {
                cleanupSawLivingChild.set(child?.isAlive == true)
                it.toFile().deleteRecursively()
            },
        )
        val output = StringBuilder()
        handler.addProcessListener(listener(output))

        try {
            handler.startNotify()
            assertTrue(
                targetExited.await(10, TimeUnit.SECONDS),
                "Target exit was not observed; rootAlive=${processAlive(rootPid)}; " +
                    "childAlive=${processAlive(pid)}; handlerTerminated=${handler.isProcessTerminated}; output=$output",
            )
            assertTrue(child?.isAlive == true)
            handler.destroyProcess()
            releaseTargetExit.countDown()

            assertTrue(handler.waitFor(10_000))
            assertTrue(handler.exitCode != 0)
            assertFalse(cleanupSawLivingChild.get(), "Owned cleanup ran before the escaped child terminated")
            val childDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (child?.isAlive == true && System.nanoTime() < childDeadline) Thread.sleep(10)
            assertFalse(child?.isAlive == true, "Cancellation left the pre-tracking child alive")
            assertFalse(Files.exists(temporary))
            assertTrue(unrelated.isAlive, "Cancellation terminated an unrelated process")
        } finally {
            releaseTargetExit.countDown()
            if (!handler.isProcessTerminated) handler.destroyProcess()
            child?.takeIf(ProcessHandle::isAlive)?.destroyForcibly()
            unrelated.destroyForcibly()
            unrelated.waitFor(5, TimeUnit.SECONDS)
            temporary.toFile().deleteRecursively()
            directory.toFile().deleteRecursively()
        }
    }

    fun testCompletedCommandReleasesAContainedBackgroundChild() {
        val directory = createTempDirectory("sequential-contained-release")
        val pid = directory.resolve("child.pid")
        var child: ProcessHandle? = null
        val handler = SequentialProcessHandler(
            directory.toFile(),
            listOf(CliCommand("contained release", escapedChildCommand(pid, inheritIo = false))),
        )

        try {
            handler.startNotify()
            assertTrue(handler.waitFor(10_000))
            assertEquals(0, handler.exitCode)
            child = ProcessHandle.of(Files.readString(pid).toLong()).orElseThrow()
            assertTrue(child.isAlive, "Normal completion terminated an intentional background child")
        } finally {
            child?.takeIf(ProcessHandle::isAlive)?.let {
                it.destroyForcibly()
                runCatching { it.onExit().get(5, TimeUnit.SECONDS) }
            }
            directory.toFile().deleteRecursively()
        }
    }

    fun testContainedCommandReceivesInputAfterSupervisorBootstrap() {
        val directory = createTempDirectory("sequential-contained-input")
        val ready = directory.resolve("ready")
        val marker = directory.resolve("input")
        val handler = SequentialProcessHandler(
            directory.toFile(),
            listOf(CliCommand("contained input", inputCommand(ready, marker))),
        )

        try {
            handler.startNotify()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!Files.isRegularFile(ready) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(Files.isRegularFile(ready), "The contained command did not start")
            handler.processInput.write("payload\n".toByteArray())
            handler.processInput.flush()

            assertTrue(handler.waitFor(10_000))
            assertEquals(0, handler.exitCode)
            assertEquals("payload", Files.readString(marker))
        } finally {
            if (!handler.isProcessTerminated) handler.destroyProcess()
            directory.toFile().deleteRecursively()
        }
    }

    fun testStoppingACommandReportsCleanupFailureBeforeTerminal() {
        val directory = createTempDirectory("sequential-cleanup-failure")
        val ready = directory.resolve("ready")
        val later = directory.resolve("later")
        val temporary = Files.createTempDirectory("affected-handler-cleanup-failure-")
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val output = StringBuilder()
        val handler = SequentialProcessHandler(
            directory.toFile(),
            listOf(
                CliCommand(
                    "cleanup failure",
                    blockingMarkerCommand(ready),
                    ownedTemporaryDirectories = listOf(temporary),
                ),
                CliCommand("later", markerCommand(later)),
            ),
            ownedTemporaryDirectoryCleanup = {
                cleanupStarted.countDown()
                releaseCleanup.await()
                false
            },
        )
        handler.addProcessListener(listener(output))

        try {
            handler.startNotify()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!Files.isRegularFile(ready) && System.nanoTime() < deadline) Thread.sleep(25)
            assertTrue(Files.isRegularFile(ready))
            handler.destroyProcess()
            assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS))

            assertFalse(handler.waitFor(100))
            assertTrue(Files.isDirectory(temporary))
            releaseCleanup.countDown()
            assertTrue(handler.waitFor(5_000))
            assertTrue(handler.exitCode != 0)
            assertTrue(output.contains("Affected could not remove its temporary output"), output.toString())
            assertFalse(Files.exists(later))
        } finally {
            releaseCleanup.countDown()
            if (!handler.isProcessTerminated) handler.destroyProcess()
            assertTrue(temporary.toFile().deleteRecursively())
        }
    }

    private fun listener(output: StringBuilder): ProcessListener = object : ProcessListener {
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            output.append(event.text)
        }
    }

    private fun markerCommand(marker: java.nio.file.Path): List<String> = if (isWindows()) {
        listOf(
            java(),
            testJavaClassPathArgument(marker.parent),
            CancellationMarkerWriter::class.java.name,
            marker.toString(),
        )
    } else {
        listOf("/bin/sh", "-c", "printf started > \"${'$'}1\"", "affected", marker.toString())
    }

    private fun blockingMarkerCommand(marker: java.nio.file.Path): List<String> = if (isWindows()) {
        listOf(
            java(),
            testJavaClassPathArgument(marker.parent),
            BlockingMarker::class.java.name,
            marker.toString(),
        )
    } else {
        listOf(
            "/bin/sh",
            "-c",
            "printf started > \"${'$'}1\"; sleep 60",
            "affected",
            marker.toString(),
        )
    }

    private fun processTreeCommand(
        directory: java.nio.file.Path,
        output: java.nio.file.Path,
        pid: java.nio.file.Path,
        ready: java.nio.file.Path,
        parentPid: java.nio.file.Path,
    ): List<String> = if (isWindows()) {
        listOf(
            java(),
            "-cp",
            directory.toString(),
            "HandlerTree",
            "parent",
            output.toString(),
            pid.toString(),
            ready.toString(),
            parentPid.toString(),
        )
    } else {
        listOf(
            "/bin/sh",
            "-c",
            "printf %s \"${'$'}${'$'}\" > \"${'$'}4\"; sleep 60 & child=${'$'}!; " +
                "printf %s \"${'$'}child\" > \"${'$'}2\"; : > \"${'$'}1/locked.txt\"; " +
                ": > \"${'$'}3\"; wait \"${'$'}child\"",
            "affected",
            output.toString(),
            pid.toString(),
            ready.toString(),
            parentPid.toString(),
        )
    }

    private fun java(): String = executable("java")

    private fun escapedChildCommand(
        pid: java.nio.file.Path,
        inheritIo: Boolean = true,
        rootPid: java.nio.file.Path? = null,
    ): List<String> {
        val classPathArgument = testJavaClassPathArgument(pid.parent)
        return listOf(
            java(),
            classPathArgument,
            PreTrackingChildSpawner::class.java.name,
            pid.toString(),
            inheritIo.toString(),
            rootPid?.toString().orEmpty(),
            classPathArgument,
        )
    }

    private fun processAlive(pid: java.nio.file.Path): Boolean = runCatching {
        ProcessHandle.of(Files.readString(pid).toLong()).map(ProcessHandle::isAlive).orElse(false)
    }.getOrDefault(false)

    private fun inputCommand(ready: java.nio.file.Path, marker: java.nio.file.Path): List<String> = listOf(
        java(),
        testJavaClassPathArgument(ready.parent),
        ContainedInputReader::class.java.name,
        ready.toString(),
        marker.toString(),
    )

    private fun sleeper(): Process {
        val command = if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("cmd.exe", "/c", "ping -n 60 127.0.0.1 > NUL")
        } else {
            listOf("sleep", "60")
        }
        return ProcessBuilder(command).directory(File(System.getProperty("java.io.tmpdir"))).start()
    }

    private fun javac(): String = executable("javac")

    private fun executable(name: String): String = File(
        System.getProperty("java.home"),
        if (System.getProperty("os.name").startsWith("Windows")) "bin/$name.exe" else "bin/$name",
    ).absolutePath

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

    private fun processTreeSource(): String =
        """
        import java.nio.channels.FileChannel;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.nio.file.StandardOpenOption;

        class HandlerTree {
            public static void main(String[] args) throws Exception {
                if (args[0].equals("child")) {
                    Path output = Path.of(args[1]);
                    Files.writeString(Path.of(args[2]), Long.toString(ProcessHandle.current().pid()));
                    try (FileChannel ignored = FileChannel.open(
                            output.resolve("locked.txt"),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE)) {
                        Files.writeString(Path.of(args[3]), "ready");
                        Thread.sleep(60_000);
                    }
                    return;
                }
                String java = ProcessHandle.current().info().command().orElseThrow();
                Files.writeString(Path.of(args[4]), Long.toString(ProcessHandle.current().pid()));
                Process child = new ProcessBuilder(
                    java, "-cp", System.getProperty("java.class.path"), "HandlerTree",
                    "child", args[1], args[2], args[3]
                ).start();
                child.waitFor();
            }
        }
        """.trimIndent()
}

private object BlockingMarker {
    @JvmStatic
    fun main(arguments: Array<String>) {
        Files.writeString(java.nio.file.Path.of(arguments.single()), "started")
        Thread.sleep(60_000)
    }
}

private object CancellationMarkerWriter {
    @JvmStatic
    fun main(arguments: Array<String>) {
        Files.writeString(java.nio.file.Path.of(arguments.single()), "started")
    }
}

private object PreTrackingChildSpawner {
    @JvmStatic
    fun main(arguments: Array<String>) {
        check(arguments.size == 4)
        arguments.getOrNull(2)?.takeIf(String::isNotEmpty)?.let { root ->
            Files.writeString(java.nio.file.Path.of(root), ProcessHandle.current().pid().toString())
        }
        val javaExecutable = ProcessHandle.current().info().command().orElseThrow()
        val builder = ProcessBuilder(
            javaExecutable,
            arguments[3],
            PreTrackingSleeper::class.java.name,
        )
        if (arguments[1].toBooleanStrict()) {
            builder.inheritIO()
        } else {
            builder.redirectInput(ProcessBuilder.Redirect.PIPE)
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            builder.redirectError(ProcessBuilder.Redirect.DISCARD)
        }
        val child = builder.start()
        Files.writeString(java.nio.file.Path.of(arguments[0]), child.pid().toString())
    }
}

private object ContainedInputReader {
    @JvmStatic
    fun main(arguments: Array<String>) {
        Files.writeString(java.nio.file.Path.of(arguments[0]), "ready")
        val line = System.`in`.bufferedReader().readLine()
        Files.writeString(java.nio.file.Path.of(arguments[1]), line)
    }
}

private object PreTrackingSleeper {
    @JvmStatic
    fun main(arguments: Array<String>) {
        check(arguments.isEmpty())
        Thread.sleep(60_000)
    }
}
