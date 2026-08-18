package com.aspix2k.affected.build

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfoRt
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContainedProcessTest {

    @Test
    fun `the target inherits the verified helper directory after its path is replaced`() {
        if (SystemInfoRt.isWindows) return
        val parent = createTempDirectory("contained-directory-identity")
        val root = parent.resolve("root").createDirectory()
        val original = parent.resolve("original")
        val marker = Path.of("marker.txt")
        val contained = ContainedProcess.prepare(
            commandLine(root, RelativeMarkerWriter::class.java.name, marker.toString()),
        )

        try {
            Files.move(root, original, StandardCopyOption.ATOMIC_MOVE)
            root.createDirectory()
            contained.start()

            assertTrue(contained.process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, contained.process.exitValue())
            assertTrue(contained.close())
            assertTrue(Files.isRegularFile(original.resolve(marker)))
            assertFalse(Files.exists(root.resolve(marker)))
        } finally {
            contained.request()
            contained.await()
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the control endpoint remains reachable when the parent prefers IPv6`() {
        val directory = createTempDirectory("contained-ipv6-parent")
        val process = ProcessBuilder(
            java(),
            "-Djava.net.preferIPv6Addresses=true",
            "-Djna.boot.library.path=${System.getProperty("jna.boot.library.path")}",
            "-Djna.nosys=true",
            "-Djna.noclasspath=true",
            testJavaClassPathArgument(directory),
            Ipv6ContainedProcessProbe::class.java.name,
            directory.toString(),
        ).directory(directory.toFile()).redirectErrorStream(true).start()

        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "The IPv6-preferred probe did not finish")
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), output)
        } finally {
            process.takeIf(Process::isAlive)?.destroyForcibly()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the supervisor uses the IDE JNA path when the startup property is unavailable`() {
        val directory = createTempDirectory("contained-jna-path")
        val property = "jna.boot.library.path"
        val original = System.getProperty(property)
        var contained: ContainedProcess? = null

        try {
            System.clearProperty(property)
            contained = ContainedProcess.prepare(
                GeneralCommandLine(listOf(java(), "-version")).withWorkDirectory(directory.toFile()),
            )
            contained.start()

            assertTrue(contained.process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, contained.process.exitValue())
            assertTrue(contained.close())
        } finally {
            contained?.request()
            contained?.await()
            if (original == null) System.clearProperty(property) else System.setProperty(property, original)
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the start handshake fails within its configured timeout`() {
        val server = ServerSocket(0, 1, ProcessSupervisorMain.controlAddress())
        val peer = Socket(ProcessSupervisorMain.controlAddress(), server.localPort)
        val socket = server.accept()
        server.close()
        val helper = TestSupervisorProcess()
        val contained = ContainedProcess(
            helper,
            socket,
            DataInputStream(socket.getInputStream()),
            DataOutputStream(socket.getOutputStream()),
            0,
            null,
            {},
            {},
            timeouts = ContainedProcessTimeouts(startMillis = 100),
        )
        val failure = AtomicReference<Throwable?>()
        val starting = Thread { failure.set(runCatching(contained::start).exceptionOrNull()) }

        try {
            starting.start()
            assertTrue(peer.getInputStream().readNBytes(Int.SIZE_BYTES * 2).isNotEmpty())
            starting.join(1_000)

            assertFalse(starting.isAlive, "The START handshake remained unbounded")
            assertTrue(failure.get() is SocketTimeoutException, failure.get().toString())
        } finally {
            peer.close()
            socket.close()
            starting.join(5_000)
            contained.request()
            contained.await()
        }
    }

    @Test
    fun `a missing release decision terminates the contained background child`() {
        val directory = createTempDirectory("contained-release-watchdog")
        val pid = directory.resolve("child.pid")
        val contained = ContainedProcess.prepare(
            commandLine(
                directory,
                WatchdogChildSpawner::class.java.name,
                pid.toString(),
                testJavaClassPathArgument(directory),
            ),
            releaseDecisionTimeoutMillis = 100,
        )
        var child: ProcessHandle? = null

        try {
            contained.start()
            assertTrue(contained.process.waitFor(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (child == null && System.nanoTime() < deadline) {
                child = runCatching {
                    ProcessHandle.of(Files.readString(pid).trim().toLong()).orElse(null)
                }.getOrNull()
                if (child == null) Thread.sleep(10)
            }
            val watchedChild = checkNotNull(child) { "The background child did not start" }
            assertTrue(watchedChild.isAlive, "The background child did not start")
            while (watchedChild.isAlive && System.nanoTime() < deadline) Thread.sleep(10)

            assertFalse(watchedChild.isAlive, "The host watchdog left the contained child alive")
            assertTrue(contained.close())
        } finally {
            contained.request()
            contained.await()
            child?.takeIf(ProcessHandle::isAlive)?.destroyForcibly()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `normal completion closes output inherited by a background child`() {
        val directory = createTempDirectory("contained-background-output")
        val pid = directory.resolve("child.pid")
        val contained = ContainedProcess.prepare(
            commandLine(
                directory,
                InheritedOutputChildSpawner::class.java.name,
                pid.toString(),
                testJavaClassPathArgument(directory),
            ),
        )
        val output = CompletableFuture.supplyAsync {
            contained.process.inputStream.bufferedReader().readText()
        }
        var child: ProcessHandle? = null

        try {
            contained.start()
            assertTrue(contained.process.waitFor(5, TimeUnit.SECONDS))
            assertEquals("root finished${System.lineSeparator()}", output.get(2, TimeUnit.SECONDS))
            assertTrue(contained.close())
            child = ProcessHandle.of(Files.readString(pid).trim().toLong()).orElseThrow()
            assertTrue(child.isAlive, "Normal completion terminated the background child")
        } finally {
            contained.request()
            contained.await()
            child?.takeIf(ProcessHandle::isAlive)?.destroyForcibly()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cancellation terminates a child in another group of the owned session`() {
        if (SystemInfoRt.isWindows) return
        val directory = createTempDirectory("contained-session-child")
        val pid = directory.resolve("child.pid")
        val contained = ContainedProcess.prepare(
            jnaCommandLine(
                directory,
                DifferentGroupChildSpawner::class.java.name,
                pid.toString(),
            ),
        )
        var child: ProcessHandle? = null

        try {
            contained.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (child == null && System.nanoTime() < deadline) {
                child = runCatching {
                    ProcessHandle.of(Files.readString(pid).substringBefore(',').toLong()).orElse(null)
                }.getOrNull()
                if (child == null) Thread.sleep(10)
            }
            val containedChild = checkNotNull(child) { "The separate process group did not start" }
            val identity = Files.readString(pid).split(',').map(String::toLong)
            assertTrue(containedChild.isAlive)
            assertEquals(contained.process.pid(), identity[1], "The child escaped the owned session")
            assertEquals(containedChild.pid(), identity[2], "The child did not establish a separate process group")

            contained.request()
            assertTrue(contained.await(), "The owned session was not proven empty")
            assertFalse(containedChild.isAlive, "Cancellation left an owned session member alive")
        } finally {
            contained.request()
            contained.await()
            child?.takeIf(ProcessHandle::isAlive)?.destroyForcibly()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `output relay never blocks on an empty pipe after the root exits`() {
        val process = EmptyInheritedPipeProcess()
        val relay = ProcessSupervisorMain.OutputRelay.start(process, true)

        try {
            assertTrue(relay.finish(100), "The relay waited for a descendant-held pipe")
            assertFalse(process.readStarted.await(100, TimeUnit.MILLISECONDS))
        } finally {
            process.releaseRead.countDown()
        }
    }

    @Test
    fun `output relay drains a final chunk published with root exit`() {
        val process = ExitTailPipeProcess()
        val relay = ProcessSupervisorMain.OutputRelay.start(process, true)

        assertTrue(relay.finish(100))
        assertTrue(process.tailRead.get(), "The relay dropped the final root output")
    }

    @Test
    fun `output relay timeout remains a failure after forced close`() {
        val process = ForcedClosePipeProcess()
        val relay = ProcessSupervisorMain.OutputRelay.start(process, true)

        assertTrue(process.readStarted.await(1, TimeUnit.SECONDS))
        assertFalse(relay.finish(50), "Forced output truncation was reported as success")
    }

    private fun commandLine(root: Path, mainClass: String, vararg arguments: String): GeneralCommandLine =
        GeneralCommandLine(
            listOf(
                java(),
                testJavaClassPathArgument(root),
                mainClass,
            ) + arguments,
        ).withWorkDirectory(root.toFile())

    private fun jnaCommandLine(root: Path, mainClass: String, vararg arguments: String): GeneralCommandLine =
        GeneralCommandLine(
            listOf(
                java(),
                "--enable-native-access=ALL-UNNAMED",
                "-Djna.boot.library.path=${System.getProperty("jna.boot.library.path")}",
                "-Djna.nosys=true",
                "-Djna.noclasspath=true",
                testJavaClassPathArgument(root),
                mainClass,
            ) + arguments,
        ).withWorkDirectory(root.toFile())

    private fun java(): String = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (SystemInfoRt.isWindows) "java.exe" else "java",
    ).toString()
}

private object RelativeMarkerWriter {
    @JvmStatic
    fun main(arguments: Array<String>) {
        Files.writeString(Path.of(arguments.single()), "started")
    }
}

private object Ipv6ContainedProcessProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val root = Path.of(arguments.single())
        val java = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (SystemInfoRt.isWindows) "java.exe" else "java",
        ).toString()
        val contained = ContainedProcess.prepare(
            GeneralCommandLine(listOf(java, "-version")).withWorkDirectory(root.toFile()),
        )
        try {
            contained.start()
            check(contained.process.waitFor(10, TimeUnit.SECONDS))
            check(contained.process.exitValue() == 0)
            check(contained.close())
        } finally {
            contained.request()
            contained.await()
        }
    }
}

private object WatchdogChildSpawner {
    @JvmStatic
    fun main(arguments: Array<String>) {
        check(arguments.size == 2)
        val java = ProcessHandle.current().info().command().orElseThrow()
        val child = ProcessBuilder(
            java,
            arguments[1],
            WatchdogSleeper::class.java.name,
        )
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        Files.writeString(Path.of(arguments[0]), child.pid().toString())
    }
}

private object WatchdogSleeper {
    @JvmStatic
    fun main(arguments: Array<String>) {
        check(arguments.isEmpty())
        Thread.sleep(60_000)
    }
}

private object InheritedOutputChildSpawner {
    @JvmStatic
    fun main(arguments: Array<String>) {
        check(arguments.size == 2)
        val java = ProcessHandle.current().info().command().orElseThrow()
        val child = ProcessBuilder(
            java,
            arguments[1],
            OutputHoldingSleeper::class.java.name,
        ).inheritIO().start()
        Files.writeString(Path.of(arguments[0]), child.pid().toString())
        println("root finished")
    }
}

private object OutputHoldingSleeper {
    @JvmStatic
    fun main(arguments: Array<String>) {
        check(arguments.isEmpty())
        Thread.sleep(60_000)
    }
}

private object DifferentGroupChildSpawner {
    @JvmStatic
    fun main(arguments: Array<String>) {
        check(arguments.size == 1)
        check(PosixTest.INSTANCE.setpgid(0, 0) == 0)
        val pid = ProcessHandle.current().pid()
        Files.writeString(
            Path.of(arguments[0]),
            "$pid,${PosixTest.INSTANCE.getsid(0)},${PosixTest.INSTANCE.getpgid(0)}",
        )
        Thread.sleep(60_000)
    }
}

private interface PosixTest : Library {
    fun setpgid(pid: Int, pgid: Int): Int

    fun getsid(pid: Int): Int

    fun getpgid(pid: Int): Int

    companion object {
        val INSTANCE: PosixTest = Native.load(Platform.C_LIBRARY_NAME, PosixTest::class.java)
    }
}

private class TestSupervisorProcess : Process() {
    private val alive = AtomicBoolean(true)
    private val terminated = CountDownLatch(1)

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        terminated.await()
        return 1
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = terminated.await(timeout, unit)

    override fun exitValue(): Int = if (alive.get()) throw IllegalThreadStateException() else 1

    override fun destroy() {
        if (alive.compareAndSet(true, false)) terminated.countDown()
    }

    override fun destroyForcibly(): Process = also { destroy() }

    override fun isAlive(): Boolean = alive.get()

    override fun pid(): Long = 0
}

private class EmptyInheritedPipeProcess : Process() {
    val readStarted = CountDownLatch(1)
    val releaseRead = CountDownLatch(1)
    private val input = object : InputStream() {
        override fun available(): Int = 0

        override fun read(): Int {
            readStarted.countDown()
            releaseRead.await()
            return -1
        }

        override fun close() = Unit
    }

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = input

    override fun getErrorStream(): InputStream = input

    override fun waitFor(): Int = 0

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

    override fun exitValue(): Int = 0

    override fun destroy() = Unit

    override fun destroyForcibly(): Process = this

    override fun isAlive(): Boolean = false
}

private class ExitTailPipeProcess : Process() {
    val tailRead = AtomicBoolean()
    private val exited = AtomicBoolean()
    private val input = object : InputStream() {
        override fun available(): Int = if (exited.get() && !tailRead.get()) 1 else 0

        override fun read(): Int = if (tailRead.compareAndSet(false, true)) 'x'.code else -1
    }

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = input

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = 0

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

    override fun exitValue(): Int = 0

    override fun destroy() = Unit

    override fun destroyForcibly(): Process = this

    override fun isAlive(): Boolean {
        exited.set(true)
        return false
    }
}

private class ForcedClosePipeProcess : Process() {
    val readStarted = CountDownLatch(1)
    private val closed = CountDownLatch(1)
    private val input = object : InputStream() {
        override fun available(): Int = 1

        override fun read(): Int {
            readStarted.countDown()
            closed.await()
            return -1
        }

        override fun close() {
            closed.countDown()
        }
    }

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = input

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = 0

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

    override fun exitValue(): Int = 0

    override fun destroy() = Unit

    override fun destroyForcibly(): Process = this

    override fun isAlive(): Boolean = true
}

internal fun testJavaClassPathArgument(directory: Path): String {
    val argumentFile = directory.resolve(".affected-test-java-classpath.args")
    Files.writeString(argumentFile, "-cp\n${javaArgumentFileValue(System.getProperty("java.class.path"))}\n")
    return "@${argumentFile.fileName}"
}

private fun javaArgumentFileValue(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n', '\r' -> error("A Java classpath cannot contain a line break")
            else -> append(character)
        }
    }
    append('"')
}
