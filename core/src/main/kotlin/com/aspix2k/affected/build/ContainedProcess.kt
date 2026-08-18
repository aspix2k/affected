package com.aspix2k.affected.build

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.SystemInfoRt
import com.sun.jna.Native
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.WinNT
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class ContainedProcess internal constructor(
    private val helper: Process,
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val helperPid: Long,
    private val windowsJob: ProcessSupervisorMain.WindowsJob?,
    private val afterTargetExit: () -> Unit,
    private val afterTerminationProof: () -> Unit,
    private val timeouts: ContainedProcessTimeouts = ContainedProcessTimeouts(),
) : ProcessTermination {

    private val decision = AtomicReference(Decision.OPEN)
    private val cancellationStarted = AtomicBoolean()
    private val releaseDecision = CountDownLatch(1)
    private val termination = CountDownLatch(1)
    private val targetCompletion = CountDownLatch(1)
    private val targetExit = AtomicReference<TargetExit?>()
    private val started = AtomicBoolean()
    private val writeLock = Any()

    @Volatile
    private var terminationProven = false

    val process: Process = SupervisedProcess(this, helper)

    override val isRequested: Boolean
        get() = decision.get() in setOf(Decision.CANCELLING, Decision.CANCELLED)

    init {
        require(timeouts.startMillis in 1..Int.MAX_VALUE.toLong())
        require(timeouts.releaseDecisionMillis > 0)
        helper.onExit().thenRun {
            if (targetCompletion.count != 0L) completeTarget(CANCEL_EXIT_CODE)
        }
    }

    fun start() {
        check(started.compareAndSet(false, true)) { "The contained process was already started" }
        check(decision.get() == Decision.OPEN) { "The contained process was cancelled before launch" }
        try {
            socket.soTimeout = timeouts.startMillis.toInt()
            writeEmptyFrame(ProcessSupervisorMain.FRAME_START)
            requireTargetStarted(readFrame(input))
            socket.soTimeout = 0
            startOwnedThread("Affected process supervisor control", ::readTargetExit)
        } catch (error: Throwable) {
            request()
            rethrow(error)
        }
    }

    override fun request() {
        while (true) {
            when (val current = decision.get()) {
                Decision.RELEASED, Decision.CANCELLED, Decision.CANCELLING -> return
                Decision.OPEN, Decision.RELEASING -> {
                    if (decision.compareAndSet(current, Decision.CANCELLING)) {
                        releaseDecision.countDown()
                        startCancellation()
                        return
                    }
                }
            }
        }
    }

    override fun await(): Boolean {
        request()
        var restoreInterrupt = Thread.interrupted()
        return try {
            val finished = termination.count == 0L ||
                termination.await(TERMINATION_AWAIT_MILLIS, TimeUnit.MILLISECONDS)
            finished && terminationProven
        } catch (_: InterruptedException) {
            restoreInterrupt = true
            false
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt()
        }
    }

    override fun close(): Boolean {
        if (!decision.compareAndSet(Decision.OPEN, Decision.RELEASING)) {
            return when (decision.get()) {
                Decision.RELEASED -> true
                Decision.CANCELLING, Decision.CANCELLED -> await()
                Decision.OPEN, Decision.RELEASING -> false
            }
        }
        releaseDecision.countDown()
        if (targetCompletion.count != 0L) return cancelSynchronously()
        val released = runCatching {
            writeEmptyFrame(ProcessSupervisorMain.FRAME_RELEASE)
            check(awaitProcess(helper, SUPERVISOR_EXIT_TIMEOUT_MILLIS))
            check(helper.exitValue() == targetExit.get()?.code)
            check(decision.compareAndSet(Decision.RELEASING, Decision.RELEASED))
            check(windowsJob?.release() != false)
            closeControlChannel()
        }.isSuccess
        if (released) return true
        return cancelSynchronously()
    }

    private fun readTargetExit() {
        runCatching {
            val exitCode = targetExitCode(readFrame(input))
            startReleaseWatchdog()
            afterTargetExit()
            completeTarget(exitCode)
        }.onFailure {
            completeTarget(CANCEL_EXIT_CODE)
            request()
        }
    }

    private fun completeTarget(exitCode: Int) {
        if (targetExit.compareAndSet(null, TargetExit(exitCode))) targetCompletion.countDown()
    }

    private fun requireTargetStarted(frame: ControlFrame) {
        if (frame.type == ProcessSupervisorMain.FRAME_ERROR) supervisorFailure(decodeError(frame.payload))
        if (frame.type != ProcessSupervisorMain.FRAME_STARTED) {
            supervisorFailure("The process supervisor returned an unexpected launch result")
        }
        val payload = DataInputStream(ByteArrayInputStream(frame.payload))
        val targetPid = payload.readLong()
        if (targetPid <= 0 || payload.available() != 0) supervisorFailure("Invalid target identity")
    }

    private fun targetExitCode(frame: ControlFrame): Int {
        if (frame.type == ProcessSupervisorMain.FRAME_ERROR) supervisorFailure(decodeError(frame.payload))
        if (frame.type != ProcessSupervisorMain.FRAME_TARGET_EXIT) {
            supervisorFailure("The process supervisor returned an unexpected terminal result")
        }
        val payload = DataInputStream(ByteArrayInputStream(frame.payload))
        val exitCode = payload.readInt()
        if (payload.available() != 0) supervisorFailure("Invalid target exit result")
        return exitCode
    }

    private fun startCancellation() {
        if (cancellationStarted.compareAndSet(false, true)) {
            startOwnedThread("Affected process containment cleanup", ::terminateContainment)
        }
    }

    private fun startReleaseWatchdog() {
        startOwnedThread("Affected process containment release watchdog") {
            val decided = runCatching {
                releaseDecision.await(timeouts.releaseDecisionMillis, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (!decided) request()
        }
    }

    private fun cancelSynchronously(): Boolean {
        decision.set(Decision.CANCELLING)
        releaseDecision.countDown()
        if (cancellationStarted.compareAndSet(false, true)) terminateContainment()
        return awaitCancellation()
    }

    private fun awaitCancellation(): Boolean {
        var restoreInterrupt = Thread.interrupted()
        return try {
            val finished = termination.count == 0L ||
                termination.await(TERMINATION_AWAIT_MILLIS, TimeUnit.MILLISECONDS)
            finished && terminationProven
        } catch (_: InterruptedException) {
            restoreInterrupt = true
            false
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt()
        }
    }

    private fun terminateContainment() {
        var proven = runCatching {
            if (SystemInfoRt.isWindows) {
                checkNotNull(windowsJob).terminateAndAwait(TERMINATION_TIMEOUT_MILLIS)
            } else {
                terminatePosixContainment(helper, helperPid)
            }
        }.getOrDefault(false)
        runCatching(afterTerminationProof).onFailure { error ->
            proven = false
            if (error is InterruptedException) Thread.currentThread().interrupt()
        }
        if (!proven && helper.isAlive) runCatching { helper.destroyForcibly() }
        closeControlChannel()
        windowsJob?.close()
        terminationProven = proven
        decision.set(Decision.CANCELLED)
        completeTarget(CANCEL_EXIT_CODE)
        termination.countDown()
    }

    private fun writeEmptyFrame(type: Int) {
        synchronized(writeLock) {
            output.writeInt(type)
            output.writeInt(0)
            output.flush()
        }
    }

    private fun closeControlChannel() {
        runCatching { socket.close() }
    }

    companion object {
        fun prepare(
            commandLine: GeneralCommandLine,
            afterTargetExit: () -> Unit = {},
            afterTerminationProof: () -> Unit = {},
            validateBeforeHelperLaunch: () -> String? = { null },
            startTimeoutMillis: Long = CONTROL_TIMEOUT_MILLIS,
            releaseDecisionTimeoutMillis: Long = HOST_RELEASE_DECISION_TIMEOUT_MILLIS,
        ): ContainedProcess = prepareContainedProcess(
            commandLine,
            afterTargetExit,
            afterTerminationProof,
            validateBeforeHelperLaunch,
            startTimeoutMillis,
            releaseDecisionTimeoutMillis,
        )
    }

    private enum class Decision {
        OPEN,
        RELEASING,
        RELEASED,
        CANCELLING,
        CANCELLED,
    }

    private class SupervisedProcess(
        private val owner: ContainedProcess,
        private val helper: Process,
    ) : Process() {
        override fun getOutputStream(): OutputStream = helper.outputStream

        override fun getInputStream(): InputStream = helper.inputStream

        override fun getErrorStream(): InputStream = helper.errorStream

        override fun waitFor(): Int {
            owner.targetCompletion.await()
            return owner.targetExit.get()?.code ?: CANCEL_EXIT_CODE
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = owner.targetCompletion.await(timeout, unit)

        override fun exitValue(): Int = owner.targetExit.get()?.code ?: throw IllegalThreadStateException()

        override fun destroy() = owner.request()

        override fun destroyForcibly(): Process = also { owner.request() }

        override fun isAlive(): Boolean = owner.targetCompletion.count != 0L

        override fun pid(): Long = helper.pid()

        override fun supportsNormalTermination(): Boolean = false
    }
}

private fun prepareContainedProcess(
    commandLine: GeneralCommandLine,
    afterTargetExit: () -> Unit,
    afterTerminationProof: () -> Unit,
    validateBeforeHelperLaunch: () -> String?,
    startTimeoutMillis: Long,
    releaseDecisionTimeoutMillis: Long,
): ContainedProcess {
    val target = targetProcess(commandLine)
    val directory = targetDirectory(target)
    val server = ServerSocket()
    var helper: Process? = null
    var socket: Socket? = null
    var job: ProcessSupervisorMain.WindowsJob? = null
    var helperPid = 0L
    var containmentVerified = false
    try {
        server.bind(InetSocketAddress(ProcessSupervisorMain.controlAddress(), 0), 1)
        server.soTimeout = CONTROL_TIMEOUT_MILLIS.toInt()
        val token = ByteArray(ProcessSupervisorMain.TOKEN_BYTES).also(SecureRandom()::nextBytes)
        helper = helperProcess(server.localPort, directory, validateBeforeHelperLaunch)
        helperPid = helper.pid()
        helper.outputStream.write(token)
        helper.outputStream.flush()
        socket = server.accept()
        if (!socket.inetAddress.isLoopbackAddress) supervisorFailure("Invalid process supervisor peer")
        socket.tcpNoDelay = true
        socket.soTimeout = CONTROL_TIMEOUT_MILLIS.toInt()
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val hello = readHello(input, token, helperPid)
        job = establishContainment(helperPid, hello)
        containmentVerified = true
        writeConfig(output, target)
        requireEmptyFrame(input, ProcessSupervisorMain.FRAME_READY)
        socket.soTimeout = 0
        return ContainedProcess(
            helper,
            socket,
            input,
            output,
            helperPid,
            job,
            afterTargetExit,
            afterTerminationProof,
            ContainedProcessTimeouts(startTimeoutMillis, releaseDecisionTimeoutMillis),
        )
    } catch (error: Throwable) {
        cleanupFailedPreparation(socket, job, helper, helperPid, containmentVerified)
        rethrow(error)
    } finally {
        runCatching { server.close() }
    }
}

private fun targetProcess(commandLine: GeneralCommandLine): ProcessBuilder =
    commandLine.toProcessBuilder().also { target ->
        requirePipe(target.redirectInput(), "input")
        requirePipe(target.redirectOutput(), "output")
        requirePipe(target.redirectError(), "error")
    }

private fun targetDirectory(target: ProcessBuilder): Path {
    val directory = target.directory()?.toPath()?.toAbsolutePath()?.normalize()
        ?: supervisorFailure("The target working directory is missing")
    if (!Files.isDirectory(directory)) supervisorFailure("The target working directory is missing")
    return directory
}

private fun establishContainment(helperPid: Long, hello: Hello): ProcessSupervisorMain.WindowsJob? {
    if (!SystemInfoRt.isWindows) {
        if (!ProcessSupervisorMain.verifyPosixSession(helperPid, hello.sid, hello.pgid)) {
            supervisorFailure("The process supervisor did not establish its containment session")
        }
        return null
    }
    if (hello.sid != 0L || hello.pgid != 0L) supervisorFailure("Invalid Windows supervisor identity")
    val job = ProcessSupervisorMain.WindowsJob.create()
    return runCatching {
        job.assign(helperPid)
        job
    }.getOrElse { error ->
        job.close()
        rethrow(error)
    }
}

private fun cleanupFailedPreparation(
    socket: Socket?,
    job: ProcessSupervisorMain.WindowsJob?,
    helper: Process?,
    helperPid: Long,
    containmentVerified: Boolean,
) {
    socket?.let { runCatching { it.close() } }
    val proven = when {
        job != null -> job.terminateAndAwait(TERMINATION_TIMEOUT_MILLIS)
        containmentVerified && helperPid > 0 && !SystemInfoRt.isWindows ->
            terminatePosixContainment(checkNotNull(helper), helperPid)
        else -> false
    }
    if (!proven && helper?.isAlive == true) runCatching { helper.destroyForcibly() }
    job?.close()
}

private fun helperProcess(
    port: Int,
    directory: Path,
    validateBeforeHelperLaunch: () -> String?,
): Process {
    val java = requiredJavaRuntime()
    val classPath = supervisorClassPath()
    val nativePath = requiredJnaNativePath()
    val arguments = mutableListOf(
        java.toString(),
        "--enable-native-access=ALL-UNNAMED",
        "-Dfile.encoding=UTF-8",
        "-Djna.boot.library.path=$nativePath",
        "-Djna.nosys=true",
        "-Djna.noclasspath=true",
    )
    arguments += listOf(
        "-cp",
        classPath.joinToString(File.pathSeparator),
        ProcessSupervisorMain::class.java.name,
        port.toString(),
    )
    val helper = ProcessBuilder(arguments).directory(directory.toFile()).apply {
        environment().keys.removeAll(HELPER_ENVIRONMENT_DENYLIST)
    }
    validateBeforeHelperLaunch()?.let { supervisorFailure("The planned working directory $it") }
    return helper.start()
}

private fun requiredJavaRuntime(): Path {
    val java = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (SystemInfoRt.isWindows) "java.exe" else "java",
    )
    if (!Files.isRegularFile(java) || !Files.isExecutable(java)) {
        supervisorFailure("The IDE Java runtime is unavailable")
    }
    return java
}

private fun supervisorClassPath(): List<Path> {
    val classPath = listOf(
        ProcessSupervisorMain::class.java,
        Native::class.java,
        WinNT::class.java,
        BaseTSD::class.java,
    ).map { type ->
        PathManager.getJarForClass(type)?.toAbsolutePath()?.normalize()
            ?: supervisorFailure("The process supervisor classpath is missing ${type.name}")
    }.distinct()
    if (classPath.any { !Files.isReadable(it) || !(Files.isDirectory(it) || Files.isRegularFile(it)) }) {
        supervisorFailure("The process supervisor classpath is unreadable")
    }
    return classPath
}

private fun requiredJnaNativePath(): String {
    val configured = System.getProperty(JNA_BOOT_LIBRARY_PATH_PROPERTY)?.takeIf(String::isNotBlank)
    val paths = configured?.split(File.pathSeparator)?.map(Path::of) ?: discoverIdeJnaNativePath()
    val invalid = paths.any { path -> !Files.isDirectory(path) || !Files.isReadable(path) }
    if (invalid) supervisorFailure("The IDE JNA native library path is unreadable")
    val nativePath = paths.joinToString(File.pathSeparator)
    if (configured == null) System.setProperty(JNA_BOOT_LIBRARY_PATH_PROPERTY, nativePath)
    return nativePath
}

private fun discoverIdeJnaNativePath(): List<Path> {
    val root = Path.of(PathManager.getHomePath(), "lib", "jna").toAbsolutePath().normalize()
    val candidates = try {
        Files.list(root).use { entries ->
            entries.filter { directory ->
                Files.isDirectory(directory) && Files.isReadable(directory) &&
                    JNA_DISPATCH_LIBRARY_NAMES.any { name ->
                        val library = directory.resolve(name)
                        Files.isRegularFile(library) && Files.isReadable(library)
                    }
            }.limit(2).toList()
        }
    } catch (_: IOException) {
        emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }
    if (candidates.size != 1) supervisorFailure("The IDE JNA native library path is unavailable")
    return candidates
}

private fun readHello(input: DataInputStream, token: ByteArray, helperPid: Long): Hello {
    val frame = readFrame(input)
    if (frame.type != ProcessSupervisorMain.FRAME_HELLO) supervisorFailure("Expected supervisor identity")
    val payload = DataInputStream(ByteArrayInputStream(frame.payload))
    if (payload.readInt() != ProcessSupervisorMain.PROTOCOL_MAGIC ||
        payload.readInt() != ProcessSupervisorMain.PROTOCOL_VERSION
    ) {
        supervisorFailure("Unsupported process supervisor protocol")
    }
    val tokenLength = payload.readInt()
    if (tokenLength != ProcessSupervisorMain.TOKEN_BYTES) supervisorFailure("Invalid supervisor token")
    val actualToken = payload.readNBytes(tokenLength)
    if (!MessageDigest.isEqual(token, actualToken)) supervisorFailure("Invalid process supervisor identity")
    val pid = payload.readLong()
    val sid = payload.readLong()
    val pgid = payload.readLong()
    if (pid != helperPid || payload.available() != 0) supervisorFailure("Invalid process supervisor identity")
    return Hello(sid, pgid)
}

private fun writeConfig(output: DataOutputStream, target: ProcessBuilder) {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { payload ->
        val arguments = target.command()
        check(arguments.isNotEmpty() && arguments.size <= ProcessSupervisorMain.MAX_ARGUMENTS)
        payload.writeInt(arguments.size)
        arguments.forEach { writeString(payload, it) }
        val environment = target.environment()
        check(environment.size <= ProcessSupervisorMain.MAX_ENVIRONMENT_ENTRIES)
        payload.writeInt(environment.size)
        environment.forEach { (name, value) ->
            writeString(payload, name)
            writeString(payload, value)
        }
        payload.writeBoolean(target.redirectErrorStream())
    }
    val config = bytes.toByteArray()
    if (config.size > ProcessSupervisorMain.MAX_CONTROL_FRAME_BYTES) {
        supervisorFailure("The contained command configuration is too large")
    }
    output.writeInt(ProcessSupervisorMain.FRAME_CONFIG)
    output.writeInt(config.size)
    output.write(config)
    output.flush()
}

private fun writeString(output: DataOutputStream, value: String) {
    if (value.indexOf('\u0000') >= 0) supervisorFailure("NUL is not allowed in process configuration")
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size > ProcessSupervisorMain.MAX_STRING_BYTES) {
        supervisorFailure("The contained command configuration is too large")
    }
    output.writeInt(bytes.size)
    output.write(bytes)
}

private fun requirePipe(redirect: ProcessBuilder.Redirect, stream: String) {
    if (redirect != ProcessBuilder.Redirect.PIPE) {
        supervisorFailure("Contained process $stream redirection is unsupported")
    }
}

private data class ControlFrame(val type: Int, val payload: ByteArray)
private data class Hello(val sid: Long, val pgid: Long)
private data class TargetExit(val code: Int)

internal data class ContainedProcessTimeouts(
    val startMillis: Long = CONTROL_TIMEOUT_MILLIS,
    val releaseDecisionMillis: Long = HOST_RELEASE_DECISION_TIMEOUT_MILLIS,
)

private fun readFrame(input: DataInputStream): ControlFrame {
    val type = input.readInt()
    val length = input.readInt()
    if (length < 0 || length > ProcessSupervisorMain.MAX_CONTROL_FRAME_BYTES) {
        throw IOException("Invalid process supervisor frame")
    }
    val payload = input.readNBytes(length)
    if (payload.size != length) throw IOException("Incomplete process supervisor frame")
    return ControlFrame(type, payload)
}

private fun requireEmptyFrame(input: DataInputStream, expectedType: Int) {
    val frame = readFrame(input)
    if (frame.type != expectedType || frame.payload.isNotEmpty()) {
        throw IOException("Unexpected process supervisor frame")
    }
}

private fun decodeError(payload: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(java.nio.ByteBuffer.wrap(payload))
    .toString()

private fun awaitProcess(process: Process, timeoutMillis: Long): Boolean {
    var restoreInterrupt = Thread.interrupted()
    return try {
        process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        restoreInterrupt = true
        false
    } finally {
        if (restoreInterrupt) Thread.currentThread().interrupt()
    }
}

private fun terminatePosixContainment(helper: Process, helperPid: Long): Boolean {
    if (helper.pid() != helperPid) return false
    return ProcessSupervisorMain.terminatePosixSession(helper.toHandle(), TERMINATION_TIMEOUT_MILLIS)
}

private fun startOwnedThread(name: String, action: () -> Unit) {
    Thread(action, name).apply {
        isDaemon = true
        start()
    }
}

private fun supervisorFailure(message: String): Nothing = throw IOException(message)

private fun rethrow(error: Throwable): Nothing = throw error

private const val CONTROL_TIMEOUT_MILLIS = 10_000L
private const val HOST_RELEASE_DECISION_TIMEOUT_MILLIS = 10_000L
private const val SUPERVISOR_EXIT_TIMEOUT_MILLIS = 5_000L
private const val TERMINATION_TIMEOUT_MILLIS = 5_000L
private const val TERMINATION_AWAIT_MILLIS = 6_000L
private const val CANCEL_EXIT_CODE = 1
private const val JNA_BOOT_LIBRARY_PATH_PROPERTY = "jna.boot.library.path"
private val JNA_DISPATCH_LIBRARY_NAMES = setOf(
    "jnidispatch.dll",
    "libjnidispatch.jnilib",
    "libjnidispatch.so",
)
private val HELPER_ENVIRONMENT_DENYLIST = setOf(
    "JAVA_TOOL_OPTIONS",
    "_JAVA_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "CLASSPATH",
)
