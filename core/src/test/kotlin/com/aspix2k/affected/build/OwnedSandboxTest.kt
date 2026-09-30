package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OwnedSandboxTest {

    @Test
    fun `sandbox is deleted after success`() {
        val root = OwnedSandbox.use("affected-sandbox-success") { sandbox ->
            populate(sandbox)
            sandbox.root
        }

        assertFalse(root.exists())
    }

    @Test
    fun `sandbox is deleted after an assertion failure`() {
        var root: File? = null

        assertFailsWith<AssertionError> {
            OwnedSandbox.use("affected-sandbox-failure") { sandbox ->
                root = sandbox.root
                populate(sandbox)
                assertTrue(false, "expected failure")
            }
        }

        assertFalse(checkNotNull(root).exists())
    }

    @Test
    fun `sandbox is deleted after an exception`() {
        var root: File? = null

        val failure = assertFailsWith<IllegalStateException> {
            OwnedSandbox.use("affected-sandbox-exception") { sandbox ->
                root = sandbox.root
                populate(sandbox)
                error("boom")
            }
        }

        assertEquals("boom", failure.message)
        assertFalse(checkNotNull(root).exists())
    }

    @Test
    fun `sandbox is deleted after a process timeout`() {
        var root: File? = null

        assertFailsWith<AssertionError> {
            OwnedSandbox.use("affected-sandbox-timeout") { sandbox ->
                root = sandbox.root
                val held = sandbox.file("held.lock")
                NativeProcessRunner.execute(
                    treeFixtureCommand(1, sandbox.directory("pids"), held),
                    sandbox.root,
                    timeoutSeconds = 6,
                )
            }
        }

        assertFalse(checkNotNull(root).exists())
    }

    @Test
    fun `undeletable sandbox fails visibly after success`() {
        val locked = IOException("simulated locked file")
        var root: Path? = null

        val failure = assertFailsWith<AssertionError> {
            OwnedSandbox.use("affected-sandbox-locked", delete = { throw locked }) { sandbox ->
                root = sandbox.root.toPath()
                populate(sandbox)
            }
        }

        try {
            assertContains(failure.message.orEmpty(), "Sandbox was not deleted")
            assertSame(locked, failure.cause)
            assertTrue(Files.exists(checkNotNull(root)))
        } finally {
            OwnedSandbox.deleteTree(checkNotNull(root))
        }
    }

    @Test
    fun `undeletable sandbox is attached to the original failure`() {
        val locked = IOException("simulated locked file")
        var root: Path? = null

        val failure = assertFailsWith<IllegalStateException> {
            OwnedSandbox.use("affected-sandbox-locked-failure", delete = { throw locked }) { sandbox ->
                root = sandbox.root.toPath()
                error("primary")
            }
        }

        try {
            assertEquals("primary", failure.message)
            assertContains(failure.suppressed.single().message.orEmpty(), "Sandbox was not deleted")
        } finally {
            OwnedSandbox.deleteTree(checkNotNull(root))
        }
    }

    @Test
    fun `transient deletion failure is retried`() {
        val attempts = AtomicInteger()

        val root = OwnedSandbox.use(
            "affected-sandbox-retry",
            delete = { path ->
                if (attempts.incrementAndGet() < 3) throw IOException("simulated transient lock")
                OwnedSandbox.deleteTree(path)
            },
        ) { sandbox ->
            populate(sandbox)
            sandbox.root
        }

        assertEquals(3, attempts.get())
        assertFalse(root.exists())
    }

    @Test
    fun `read-only directory that really cannot be deleted fails visibly`() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        var guarded: Path? = null
        var root: Path? = null

        val failure = runCatching {
            OwnedSandbox.use("affected-sandbox-readonly") { sandbox ->
                root = sandbox.root.toPath()
                val directory = sandbox.directory("guarded")
                File(directory, "entry.txt").writeText("entry")
                guarded = directory.toPath()
                Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"))
            }
        }.exceptionOrNull()

        try {
            assumeTrue(
                "Directory permissions are not enforced for this user",
                failure != null || !Files.exists(checkNotNull(root)),
            )
            assertTrue(failure is AssertionError, failure.toString())
            assertContains(failure.message.orEmpty(), "Sandbox was not deleted")
            assertTrue(Files.exists(checkNotNull(root)))
        } finally {
            guarded?.takeIf(Files::exists)?.let {
                Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxr-xr-x"))
            }
            root?.let(OwnedSandbox::deleteTree)
        }
    }

    private fun populate(sandbox: OwnedSandbox) {
        File(sandbox.directory("nested/deeper"), "file.txt").writeText("content")
        sandbox.file("root.txt").writeText("content")
    }
}
