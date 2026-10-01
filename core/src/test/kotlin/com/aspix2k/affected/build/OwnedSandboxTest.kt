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
    fun `read-only nested entries are made writable and deleted`() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val link = Files.createTempFile("affected-sandbox-link-target", ".txt")
        val outside = Files.createTempDirectory("affected-sandbox-outside")
        val outsideFile = Files.createFile(outside.resolve("keep.txt"))
        Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            val root = OwnedSandbox.use("affected-sandbox-readonly") { sandbox ->
                val install = sandbox.directory("install/deep/deeper")
                val file = File(install, "entry.txt").apply { writeText("entry") }
                val sibling = File(sandbox.directory("install/deep"), "sibling.txt").apply { writeText("sibling") }
                Files.createSymbolicLink(sandbox.file("install/outside").toPath(), outside)
                Files.createSymbolicLink(sandbox.file("install/file-link").toPath(), link)
                restrict(file, "r--r--r--")
                restrict(sibling, "r--r--r--")
                restrict(install, "r-xr-xr-x")
                restrict(install.parentFile, "r-xr-xr-x")
                restrict(sandbox.file("install"), "---------")
                sandbox.root
            }

            assertFalse(root.exists())
            assertTrue(Files.exists(outsideFile))
            assertEquals(
                PosixFilePermissions.fromString("r-xr-xr-x"),
                Files.getPosixFilePermissions(outside),
            )
            assertTrue(Files.exists(link))
        } finally {
            Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rwxr-xr-x"))
            OwnedSandbox.deleteTree(outside)
            Files.deleteIfExists(link)
        }
    }

    @Test
    fun `remove attaches an undeletable directory to the primary failure`() {
        val locked = IOException("simulated locked file")
        val root = Files.createTempDirectory("affected-sandbox-remove")
        val primary = IllegalStateException("primary")

        try {
            OwnedSandbox.remove(root.toFile(), primary) { throw locked }

            assertContains(primary.suppressed.single().message.orEmpty(), "Sandbox was not deleted")
            assertFailsWith<AssertionError> { OwnedSandbox.remove(root.toFile()) { throw locked } }
        } finally {
            OwnedSandbox.deleteTree(root)
        }
    }

    private fun restrict(file: File, permissions: String) {
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(permissions))
    }

    private fun populate(sandbox: OwnedSandbox) {
        File(sandbox.directory("nested/deeper"), "file.txt").writeText("content")
        sandbox.file("root.txt").writeText("content")
    }
}
