package com.aspix2k.affected.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObservedReadsTest {

    private val work = File("/repo/web")

    @Test
    fun `decoded descriptors identify paths after cwd changes and relative directory opens`() {
        assertEquals(
            File("/repo/shared.txt"),
            straceRead(
                """42 openat(AT_FDCWD</repo/web/nested>, "../../shared.txt", O_RDONLY) = 3</repo/shared.txt>""", work,
            ),
        )
        assertEquals(
            File("/repo/data/input.txt"),
            straceRead("""42 openat(7</repo/data>, "input.txt", O_RDONLY) = 8</repo/data/input.txt>""", work),
        )
    }

    @Test
    fun `unfinished opens and executions do not prove a successful read`() {
        assertNull(straceRead("""42 openat(AT_FDCWD, "/repo/data.txt", O_RDONLY <unfinished ...>""", work))
        assertNull(straceRead("""42 execve("/repo/tool", ["tool"], 0x123 <unfinished ...>""", work))
    }

    @Test
    fun `a file opened for reading or executed is a read`() {
        assertEquals(
            File("/repo/shared.txt"),
            straceRead("""412 openat(AT_FDCWD, "/repo/shared.txt", O_RDONLY|O_CLOEXEC) = 3""", work),
        )
        assertEquals(
            File("/repo/tool.sh"),
            straceRead("""execve("/repo/tool.sh", ["tool.sh"], 0x7ff /* 3 vars */) = 0""", work),
        )
        assertEquals(File("/repo/web/data.json"), straceRead("""open("data.json", O_RDWR) = 4""", work))
        assertEquals(File("/repo/a.txt"), straceRead("""openat(7, "/repo/a.txt", O_RDONLY) = 8""", work))
    }

    @Test
    fun `a failed open, a write and an unrelated call are not reads`() {
        assertNull(straceRead("""openat(AT_FDCWD, "/repo/none", O_RDONLY) = -1 ENOENT (No such file)""", work))
        assertNull(straceRead("""openat(AT_FDCWD, "/repo/out.log", O_WRONLY|O_CREAT|O_TRUNC, 0644) = 5""", work))
        assertNull(straceRead("""412 +++ exited with 0 +++""", work))
        assertNull(straceRead("""openat(7</repo/api>, "data.json", O_RDONLY) = 8""", work))
    }

    @Test
    fun `an escaped path is decoded`() {
        assertEquals(
            File("/repo/данные \"1\".txt"),
            straceRead(
                "openat(AT_FDCWD, \"/repo/" + """\320\264\320\260\320\275\320\275\321\213\320\265""" +
                    """ \"1\".txt", O_RDONLY) = 3""",
                work,
            ),
        )
    }
}
