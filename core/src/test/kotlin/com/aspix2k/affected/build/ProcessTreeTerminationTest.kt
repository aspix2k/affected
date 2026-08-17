package com.aspix2k.affected.build

import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProcessTreeTerminationTest {

    private val executor = Executors.newScheduledThreadPool(2)

    @org.junit.After
    fun closeExecutor() {
        executor.shutdownNow()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test
    fun `termination discovers a real child created after the first scan`() {
        val directory = createTempDirectory("affected-late-child").toFile()
        val trigger = File(directory, "trigger")
        val pid = File(directory, "child.pid")
        val root = javaProcess(LateChildSpawner::class.java.name, trigger.path, pid.path)
        var child: ProcessHandle? = null
        val termination = ProcessTreeTermination(
            root.toHandle(),
            afterInitialScan = {
                trigger.writeText("spawn")
                waitForFile(pid)
                child = ProcessHandle.of(pid.readText().trim().toLong()).orElse(null)
                assertNotNull(child)
                assertTrue(child.isAlive)
            },
            timeoutNanos = TimeUnit.SECONDS.toNanos(10),
            executor = executor,
        )

        try {
            termination.request()

            assertTrue(termination.await())
            assertFalse(root.toHandle().isAlive)
            assertFalse(child?.isAlive ?: true)
        } finally {
            stop(root)
            child?.takeIf(ProcessHandle::isAlive)?.destroyForcibly()
            assertTrue(directory.deleteRecursively())
        }
    }

    @Test
    fun `termination preserves interruption after bounded cleanup`() {
        val root = sleeper()
        try {
            Thread.currentThread().interrupt()

            assertTrue(ProcessTreeTermination(root.toHandle(), executor = executor).await())
            assertTrue(Thread.interrupted())
            assertFalse(root.toHandle().isAlive)
        } finally {
            Thread.interrupted()
            stop(root)
        }
    }

    @Test
    fun `tracking retains a child after its parent exits`() {
        val directory = createTempDirectory("affected-reparented-child").toFile()
        val release = File(directory, "release")
        val pid = File(directory, "child.pid")
        val root = javaProcess(TrackedChildSpawner::class.java.name, release.path, pid.path)
        val tracked = CountDownLatch(1)
        var child: ProcessHandle? = null
        val termination = ProcessTreeTermination(
            root.toHandle(),
            afterTrackingScan = { processes ->
                if (pid.isFile) {
                    ProcessHandle.of(pid.readText().trim().toLong()).orElse(null)?.let { handle ->
                        if (handle in processes) {
                            child = handle
                            release.writeText("release")
                            tracked.countDown()
                        }
                    }
                }
            },
            executor = executor,
        )

        try {
            assertTrue(tracked.await(5, TimeUnit.SECONDS))
            assertTrue(root.waitFor(5, TimeUnit.SECONDS))
            val trackedChild = assertNotNull(child)
            assertTrue(trackedChild.isAlive)

            assertTrue(termination.await())
            assertFalse(trackedChild.isAlive)
        } finally {
            termination.close()
            stop(root)
            child?.takeIf(ProcessHandle::isAlive)?.destroyForcibly()
            assertTrue(directory.deleteRecursively())
        }
    }

    @Test
    fun `an incomplete process scan cannot prove termination`() {
        val root = sleeper()
        val termination = ProcessTreeTermination(
            root.toHandle(),
            childSnapshot = { throw IOException("incomplete scan") },
            timeoutNanos = TimeUnit.MILLISECONDS.toNanos(100),
            executor = executor,
        )
        try {
            assertFalse(termination.await())
            assertFalse(root.toHandle().isAlive)
        } finally {
            stop(root)
        }
    }

    @Test
    fun `process count limit bounds an incomplete tree`() {
        val root = sleeper()
        val first = sleeper()
        val second = sleeper()
        val termination = ProcessTreeTermination(
            root.toHandle(),
            childSnapshot = { process ->
                if (process.pid() == root.pid()) listOf(first.toHandle(), second.toHandle()) else emptyList()
            },
            maxProcesses = 2,
            executor = executor,
        )
        try {
            assertFalse(termination.await())
            assertFalse(root.toHandle().isAlive)
        } finally {
            stop(root)
            stop(first)
            stop(second)
        }
    }

    @Test
    fun `parent remains alive until its force killed child becomes terminal`() {
        val parentAlive = AtomicBoolean(true)
        val childAlive = AtomicBoolean(true)
        val childKillRequested = AtomicBoolean()
        val parent = processHandle(
            pid = 1,
            alive = parentAlive::get,
            destroy = { parentAlive.getAndSet(false) },
        )
        val child = processHandle(
            pid = 2,
            alive = {
                if (childKillRequested.get() && parentAlive.get()) childAlive.set(false)
                childAlive.get()
            },
            destroy = {
                childKillRequested.set(true)
                true
            },
        )
        val termination = ProcessTreeTermination(
            root = parent,
            childSnapshot = { process -> if (process == parent) listOf(child) else emptyList() },
            timeoutNanos = TimeUnit.MILLISECONDS.toNanos(250),
            executor = executor,
        )

        assertTrue(termination.await())
        assertFalse(parentAlive.get())
        assertFalse(childAlive.get())
    }

    @Test
    fun `request continues from a terminated child to its living parent`() {
        val parentAlive = AtomicBoolean(true)
        val childAlive = AtomicBoolean(true)
        val childKillRequested = AtomicBoolean()
        val initialPass = CountDownLatch(1)
        val parent = processHandle(
            pid = 1,
            alive = parentAlive::get,
            destroy = { parentAlive.getAndSet(false) },
        )
        val child = processHandle(
            pid = 2,
            alive = {
                if (childKillRequested.get() && parentAlive.get()) childAlive.set(false)
                childAlive.get()
            },
            destroy = {
                childKillRequested.set(true)
                true
            },
        )
        val termination = ProcessTreeTermination(
            root = parent,
            childSnapshot = { process -> if (process == parent) listOf(child) else emptyList() },
            afterInitialPass = initialPass::countDown,
            timeoutNanos = TimeUnit.MILLISECONDS.toNanos(250),
            executor = executor,
        )

        try {
            termination.request()
            assertTrue(initialPass.await(1, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (parentAlive.get() && System.nanoTime() < deadline) Thread.sleep(10)

            assertFalse(parentAlive.get())
            assertFalse(childAlive.get())
        } finally {
            termination.close()
        }
    }

    private fun sleeper(): Process {
        val command = if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("cmd.exe", "/c", "ping -n 60 127.0.0.1 > NUL")
        } else {
            listOf("sleep", "60")
        }
        return ProcessBuilder(command).directory(File(System.getProperty("java.io.tmpdir"))).start()
    }

    private fun processHandle(
        pid: Long,
        alive: () -> Boolean,
        destroy: () -> Boolean,
    ): ProcessHandle = Proxy.newProxyInstance(
        ProcessHandle::class.java.classLoader,
        arrayOf(ProcessHandle::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            "pid" -> pid
            "isAlive" -> alive()
            "destroy", "destroyForcibly" -> destroy()
            "equals" -> proxy === arguments?.singleOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "ProcessHandle[$pid]"
            else -> error("Unexpected ProcessHandle call: ${method.name}")
        }
    } as ProcessHandle

    private fun javaProcess(mainClass: String, vararg arguments: String): Process {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            val script = when (mainClass) {
                LateChildSpawner::class.java.name ->
                    "while [ ! -f \"${'$'}1\" ]; do sleep 0.01; done; " +
                        "sleep 60 & child=${'$'}!; printf %s \"${'$'}child\" > \"${'$'}2\"; wait \"${'$'}child\""
                TrackedChildSpawner::class.java.name ->
                    "sleep 60 & child=${'$'}!; printf %s \"${'$'}child\" > \"${'$'}2\"; " +
                        "while [ ! -f \"${'$'}1\" ]; do sleep 0.01; done"
                else -> error("Unsupported process fixture: $mainClass")
            }
            return ProcessBuilder("/bin/sh", "-c", script, "affected", *arguments).start()
        }
        return ProcessBuilder(
            javaExecutable(),
            "-cp",
            System.getProperty("java.class.path"),
            mainClass,
            *arguments,
        ).start()
    }

    private fun javaExecutable(): String = File(
        System.getProperty("java.home"),
        if (System.getProperty("os.name").startsWith("Windows")) "bin/java.exe" else "bin/java",
    ).path

    private fun waitForFile(file: File) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!file.isFile && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(file.isFile, "Child pid was not published")
    }

    private fun stop(process: Process) {
        if (process.isAlive) process.destroyForcibly()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
    }
}

private object LateChildSpawner {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val trigger = File(arguments[0])
        val pid = File(arguments[1])
        while (!trigger.isFile) Thread.sleep(10)
        val command = if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("cmd.exe", "/c", "ping -n 60 127.0.0.1 > NUL")
        } else {
            listOf("sleep", "60")
        }
        val child = ProcessBuilder(command).start()
        pid.writeText(child.pid().toString())
        child.waitFor()
    }
}

private object TrackedChildSpawner {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val release = File(arguments[0])
        val pid = File(arguments[1])
        val command = if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("cmd.exe", "/c", "ping -n 60 127.0.0.1 > NUL")
        } else {
            listOf("sleep", "60")
        }
        val child = ProcessBuilder(command).start()
        pid.writeText(child.pid().toString())
        while (!release.isFile) Thread.sleep(10)
    }
}
