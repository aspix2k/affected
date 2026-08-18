package com.aspix2k.affected.build

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfoRt
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

    private fun commandLine(root: Path, mainClass: String, vararg arguments: String): GeneralCommandLine =
        GeneralCommandLine(
            listOf(
                java(),
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
