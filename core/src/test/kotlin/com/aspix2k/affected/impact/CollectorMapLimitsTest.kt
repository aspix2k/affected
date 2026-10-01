package com.aspix2k.affected.impact

import java.lang.reflect.InvocationTargetException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class CollectorMapLimitsTest : CollectorMapFixture() {

    @Test
    fun `manifests accept exactly the size limit and no more`() = withDirectory { root ->
        val limit = 16 * 1024 * 1024
        listOf(limit to true, limit + 1 to false).forEachIndexed { index, (size, accepted) ->
            val task = task(Files.createDirectory(root.resolve("size-$index")))
            worker(task, "worker-1", "AlphaTest", dependency("Alpha", "alpha-1"))
            Files.writeString(task.resolve("task.manifest"), paddedTaskManifest(size))

            val candidate = CollectorMapReader.read(task, "collector-1", "run-1")

            assertEquals(accepted, candidate != null, "size $size")
        }
    }

    @Test
    fun `manifests accept exactly the line limit and no more`() = withDirectory { root ->
        val limit = 200_000
        listOf(limit to true, limit + 1 to false).forEachIndexed { index, (lines, accepted) ->
            val task = task(Files.createDirectory(root.resolve("lines-$index")))
            worker(task, "worker-1", "AlphaTest", dependency("Alpha", "alpha-1"))
            val tests = (0 until lines - 2).joinToString("") { "test=${encode(uniqueName(it))}\n" }
            Files.writeString(task.resolve("expected.manifest"), "format=1\nsupported=true\n$tests")

            val candidate = CollectorMapReader.read(task, "collector-1", "run-1")

            assertEquals(accepted, candidate != null, "lines $lines")
        }
    }

    @Test
    fun `directory listings accept exactly the file limit and no more`() = withDirectory { root ->
        val limit = 100_000
        listOf(limit to true, limit + 1 to false).forEachIndexed { index, (entries, accepted) ->
            val archive = root.resolve("entries-$index.zip")
            ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                repeat(entries) {
                    zip.putNextEntry(ZipEntry("f$it"))
                    zip.closeEntry()
                }
            }
            FileSystems.newFileSystem(archive).use { archiveSystem ->
                val outcome = runCatching { listEntries(archiveSystem.getPath("/")).size }

                assertEquals(accepted, outcome.isSuccess, "entries $entries")
                if (accepted) assertEquals(limit, outcome.getOrNull())
            }
        }
    }

    private fun paddedTaskManifest(size: Int): String {
        val prefix = "format=1\ntask=${encode("root|:app|testDebugUnitTest")}\nruntime="
        val remaining = size - prefix.length - "\ninput=\nall=true\n".length
        for (inputBytes in 1..3) {
            val runtimeLength = remaining - encodedLength(inputBytes)
            val runtimeBytes = (0..runtimeLength).firstOrNull { encodedLength(it) == runtimeLength }
            if (runtimeBytes != null) {
                return prefix + encode("r".repeat(runtimeBytes)) +
                    "\ninput=${encode("i".repeat(inputBytes))}\nall=true\n"
            }
        }
        error("No padding for $size")
    }

    private fun encodedLength(bytes: Int): Int = bytes / 3 * 4 + listOf(0, 2, 3)[bytes % 3]

    private fun uniqueName(index: Int): String = listOf(index % 94, index / 94 % 94, index / (94 * 94))
        .map { (33 + it).toChar() }
        .joinToString("")

    private fun listEntries(directory: Path): List<Path> {
        val method = Class.forName("com.aspix2k.affected.impact.CollectorMapIOKt")
            .getDeclaredMethod("list", Path::class.java)
        method.isAccessible = true
        return try {
            @Suppress("UNCHECKED_CAST")
            method.invoke(null, directory) as List<Path>
        } catch (failure: InvocationTargetException) {
            throw failure.cause ?: failure
        }
    }
}
