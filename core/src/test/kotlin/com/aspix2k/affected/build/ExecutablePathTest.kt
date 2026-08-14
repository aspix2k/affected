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
        File(dir, "sqlc").mkdir()

        assertEquals("sqlc", resolveExecutable("sqlc", dir.path, null))
    }

    @Test
    fun `a missing program keeps the original name`() {
        val dir = createTempDirectory("exe-missing").toFile()

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
        check(hidden.setExecutable(false))

        assertEquals("sqlc", resolveExecutable("sqlc", hidden.path, null))
        hidden.setReadable(true)
        hidden.setExecutable(true)
    }
}
