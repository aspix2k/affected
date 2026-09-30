package com.aspix2k.affected.build

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal class OwnedSandbox private constructor(
    val root: File,
    private val delete: (Path) -> Unit,
) : AutoCloseable {

    fun directory(name: String): File = File(root, name).also { directory ->
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create sandbox directory: $directory" }
    }

    fun file(name: String): File = File(root, name)

    override fun close() = remove(root, delete)

    companion object {
        fun open(
            prefix: String,
            canonical: Boolean = false,
            delete: (Path) -> Unit = ::deleteTree,
        ): OwnedSandbox {
            val created = Files.createTempDirectory(prefix)
            return OwnedSandbox(if (canonical) created.toRealPath().toFile() else created.toFile(), delete)
        }

        fun <T> use(
            prefix: String,
            canonical: Boolean = false,
            delete: (Path) -> Unit = ::deleteTree,
            block: (OwnedSandbox) -> T,
        ): T {
            val sandbox = open(prefix, canonical, delete)
            var failure: Throwable? = null
            try {
                return block(sandbox)
            } catch (thrown: Throwable) {
                failure = thrown
                throw thrown
            } finally {
                closeAll(listOf(sandbox), failure)
            }
        }

        fun remove(directory: File, delete: (Path) -> Unit = ::deleteTree) {
            cleanupFailure(directory.toPath(), delete)?.let { throw it }
        }

        fun closeAll(sandboxes: Iterable<OwnedSandbox>, primary: Throwable? = null) {
            var failure = primary
            sandboxes.forEach { sandbox ->
                try {
                    sandbox.close()
                } catch (cleanup: AssertionError) {
                    val first = failure
                    if (first == null) failure = cleanup else first.addSuppressed(cleanup)
                }
            }
            if (failure != null && failure !== primary) throw failure
        }

        fun deleteTree(path: Path) {
            if (!exists(path)) return
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                        if (failure != null) throw failure
                        Files.delete(directory)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }

        private fun cleanupFailure(path: Path, delete: (Path) -> Unit): AssertionError? {
            var last: IOException? = null
            for (attempt in 1..DELETE_ATTEMPTS) {
                try {
                    delete(path)
                } catch (failure: IOException) {
                    last = failure
                }
                if (!exists(path)) return null
                if (attempt < DELETE_ATTEMPTS) Thread.sleep(DELETE_RETRY_MILLIS)
            }
            return AssertionError("Sandbox was not deleted: $path", last)
        }

        private fun exists(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

        private const val DELETE_ATTEMPTS = 5
        private const val DELETE_RETRY_MILLIS = 100L
    }
}
