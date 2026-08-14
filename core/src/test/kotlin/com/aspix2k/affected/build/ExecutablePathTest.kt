package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class ExecutablePathTest {

    @Test
    fun `a PATH program with a PATHEXT suffix is chosen`() {
        val dir = createTempDirectory("exe-pathext").toFile()
        val exe = File(dir, "sqlc.exe").apply {
            writeText("x")
            check(setExecutable(true))
        }

        assertEquals(exe.canonicalFile.invariantSeparatorsPath, resolveExecutable("sqlc", dir.path, ".EXE;.CMD"))
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

        assertEquals(file.canonicalFile.invariantSeparatorsPath, resolveExecutable(file.path, "/nope", null))
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
