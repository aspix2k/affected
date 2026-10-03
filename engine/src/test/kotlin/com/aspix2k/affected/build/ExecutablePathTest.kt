package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ExecutablePathTest {

    @Test
    fun `a PATH program with a PATHEXT suffix is chosen`() {
        val dir = createTempDirectory("exe-pathext").toFile()
        val exe = File(dir, "sqlc.exe").apply {
            writeText("x")
            check(setExecutable(true))
        }

        val resolved = File(resolveExecutable("sqlc", dir.path, ".EXE;.CMD"))
        assertEquals(exe.canonicalFile, resolved.canonicalFile)
    }

    @Test
    fun `a directory on PATH is not an executable`() {
        val dir = createTempDirectory("exe-dir").toFile()
        File(dir, "sqlc").apply {
            check(mkdir())
            check(setExecutable(true))
        }

        assertEquals("sqlc", resolveExecutable("sqlc", dir.path, null))
    }

    @Test
    fun `a missing program keeps the original name`() {
        val dir = createTempDirectory("exe-missing").toFile()

        assertEquals("sqlc", resolveExecutable("sqlc", dir.path, null))
        assertEquals("sqlc", resolveExecutable("sqlc", null, null))
    }

    @Test
    fun `a unique PATH program is not resolved as a cwd-relative file`() {
        val dir = createTempDirectory("exe-unique").toFile()
        val name = "sqlc-pit-${dir.name}"
        val exe = File(dir, name).apply {
            writeText("x")
            check(setExecutable(true))
        }

        val resolved = resolveExecutable(name, dir.path, null)
        assertEquals(exe.absoluteFile.normalize().invariantSeparatorsPath, resolved)
        assertFalse(File(name).exists())
    }

    @Test
    fun `a blank name is not looked up on PATH`() {
        val dir = createTempDirectory("exe-blank").toFile()
        File(dir, "sqlc").apply {
            writeText("x")
            check(setExecutable(true))
        }

        File(dir, ".EXE").apply {
            writeText("x")
            check(setExecutable(true))
        }
        File(dir, "  ").apply {
            writeText("x")
            check(setExecutable(true))
        }

        assertEquals("", resolveExecutable("", dir.path, ".EXE"))
        assertEquals("  ", resolveExecutable("  ", dir.path, null))
    }

    @Test
    fun `a backslash in the name is a path, not a PATH lookup`() {
        val dir = createTempDirectory("exe-backslash").toFile()
        File(dir, "missing\\tool").apply {
            writeText("x")
            check(setExecutable(true))
        }

        assertEquals("missing\\tool", resolveExecutable("missing\\tool", dir.path, null))
    }

    @Test
    fun `a PATHEXT token without a leading dot is ignored`() {
        val dir = createTempDirectory("exe-pathext-dot").toFile()
        File(dir, "sqlcEXE").apply {
            writeText("x")
            check(setExecutable(true))
        }

        assertEquals("sqlc", resolveExecutable("sqlc", dir.path, "EXE"))
    }

    @Test
    fun `a non-executable file is not chosen`() {
        val dir = createTempDirectory("exe-noexec").toFile()
        File(dir, "sqlc").apply {
            writeText("x")
            check(setExecutable(false))
        }

        assertEquals("sqlc", resolveExecutable("sqlc", dir.path, null))
    }

    @Test
    fun `an absolute executable is used`() {
        val file = File(createTempDirectory("exe-abs").toFile(), "tool").apply {
            writeText("x")
            check(setExecutable(true))
        }

        assertEquals(file.absoluteFile.normalize().invariantSeparatorsPath, resolveExecutable(file.path, "/nope", null))
    }

    @Test
    fun `a dotted path is normalized instead of returned as typed`() {
        val dir = createTempDirectory("exe-dot").toFile()
        val file = File(dir, "tool").apply {
            writeText("x")
            check(setExecutable(true))
        }
        val dotted = File(dir, ".").path + File.separator + "tool"

        val resolved = resolveExecutable(dotted, "/nope", null)
        assertEquals(file.absoluteFile.normalize().invariantSeparatorsPath, resolved)
        assertFalse(resolved.contains("/./") || resolved.contains("\\.\\"))
    }

    @Test
    fun `a rustup proxy keeps the requested program name`() {
        val dir = createTempDirectory("exe-proxy").toFile()
        val rustup = File(dir, "rustup").apply {
            writeText("x")
            check(setExecutable(true))
        }
        val cargo = File(dir, "cargo")
        assumeTrue(runCatching { Files.createSymbolicLink(cargo.toPath(), rustup.toPath()) }.isSuccess)

        val resolved = resolveExecutable("cargo", dir.path, null)
        assertEquals(cargo.absoluteFile.normalize().invariantSeparatorsPath, resolved)
        assertFalse(resolved.endsWith("rustup"))
        assertEquals(
            cargo.absoluteFile.normalize().invariantSeparatorsPath,
            resolveExecutable(cargo.path, "/nope", null),
        )
    }

    @Test
    fun `an unreadable PATH directory is skipped`() {
        val hidden = createTempDirectory("exe-hidden").toFile()
        File(hidden, "sqlc").apply {
            writeText("x")
            check(setExecutable(true))
        }
        check(hidden.setReadable(false))
        check(hidden.setExecutable(true))
        assumeTrue(!hidden.canRead() && hidden.canExecute())

        assertEquals("sqlc", resolveExecutable("sqlc", hidden.path, null))
        hidden.setReadable(true)
    }
}
