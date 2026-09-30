package com.aspix2k.affected.build

import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

internal class OwnedSandbox private constructor(
    val root: File,
    private val delete: (Path) -> Unit,
) : AutoCloseable {

    fun directory(name: String): File = File(root, name).also { directory ->
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create sandbox directory: $directory" }
    }

    fun file(name: String): File = File(root, name)

    override fun close() = remove(root, delete = delete)

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

        fun remove(directory: File, primary: Throwable? = null, delete: (Path) -> Unit = ::deleteTree) {
            val cleanup = cleanupFailure(directory.toPath(), delete) ?: return
            if (primary == null) throw cleanup
            primary.addSuppressed(cleanup)
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
                    override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                        makeWritable(directory, attributes)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                        makeWritable(file, attributes)
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, failure: IOException): FileVisitResult {
                        val attributes = runCatching {
                            Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        }.getOrNull()
                        if (failure !is AccessDeniedException || attributes?.isDirectory != true) throw failure
                        if (!makeWritable(file, attributes)) throw failure
                        deleteTree(file)
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

        private fun makeWritable(path: Path, attributes: BasicFileAttributes): Boolean {
            val directory = attributes.isDirectory
            if (!directory && !attributes.isRegularFile) return false
            val required = if (directory) OWNER_DIRECTORY_ACCESS else OWNER_FILE_ACCESS
            val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
            if (posix != null) {
                val permissions = Files.getPosixFilePermissions(path)
                if (permissions.containsAll(required)) return false
                Files.setPosixFilePermissions(path, permissions + required)
                return true
            }
            val dos = Files.getFileAttributeView(path, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            if (dos == null || !dos.readAttributes().isReadOnly) return false
            dos.setReadOnly(false)
            return true
        }

        private fun cleanupFailure(path: Path, delete: (Path) -> Unit): AssertionError? {
            var last: IOException? = null
            var interrupted = Thread.interrupted()
            try {
                for (attempt in 1..DELETE_ATTEMPTS) {
                    try {
                        delete(path)
                    } catch (failure: IOException) {
                        last = failure
                    }
                    if (!exists(path)) return null
                    if (attempt < DELETE_ATTEMPTS && !pause()) interrupted = true
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
            return AssertionError("Sandbox was not deleted: $path", last)
        }

        private fun pause(): Boolean = try {
            Thread.sleep(DELETE_RETRY_MILLIS)
            true
        } catch (_: InterruptedException) {
            false
        }

        private fun exists(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

        private val OWNER_FILE_ACCESS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        private val OWNER_DIRECTORY_ACCESS = OWNER_FILE_ACCESS + PosixFilePermission.OWNER_EXECUTE
        private const val DELETE_ATTEMPTS = 21
        private const val DELETE_RETRY_MILLIS = 100L
    }
}
