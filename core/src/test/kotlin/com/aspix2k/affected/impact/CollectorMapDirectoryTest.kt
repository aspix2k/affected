package com.aspix2k.affected.impact

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CollectorMapDirectoryTest : CollectorMapFixture() {

    @Test
    fun `an unwritable task or worker directory invalidates task output`() = withDirectory { root ->
        val targets = mapOf(
            "task" to { task: Path -> task },
            "worker" to { task: Path -> workerDirectory(task, "worker-1") },
        )
        targets.entries.forEachIndexed { index, (name, target) ->
            val task = task(Files.createDirectory(root.resolve("write-$index")))
            worker(task, "worker-1", "AlphaTest", dependency("Alpha", "alpha-1"))

            withPermissions(target(task), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE)) {
                assertNull(CollectorMapReader.read(task, "collector-1", "run-1"), name)
            }
            assertNotNull(CollectorMapReader.read(task, "collector-1", "run-1"), name)
        }
    }

    @Test
    fun `an unreadable task directory invalidates task output`() = withDirectory { root ->
        val task = task(root)
        worker(task, "worker-1", "AlphaTest", dependency("Alpha", "alpha-1"))

        withPermissions(task, setOf(PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)) {
            assertNull(CollectorMapReader.read(task, "collector-1", "run-1"))
        }
    }

    @Test
    fun `symlinked task and worker directories invalidate task output`() = withDirectory { root ->
        val task = task(Files.createDirectory(root.resolve("real")))
        worker(task, "worker-1", "AlphaTest", dependency("Alpha", "alpha-1"))
        val link = Files.createSymbolicLink(root.resolve("link"), task)

        assertNotNull(CollectorMapReader.read(task, "collector-1", "run-1"))
        assertNull(CollectorMapReader.read(link, "collector-1", "run-1"))

        val moved = Files.move(workerDirectory(task, "worker-1"), root.resolve("worker-${sha256("worker-1")}"))
        Files.createSymbolicLink(workerDirectory(task, "worker-1"), moved)

        assertNull(CollectorMapReader.read(task, "collector-1", "run-1"))
    }
}
