package com.aspix2k.affected.build

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class CliConformanceRepository(root: File) {
    private val root = root.toPath().also { configured ->
        require(configured.isAbsolute) { "CLI conformance repository root must be absolute: $configured" }
    }.normalize().let { configured ->
        require(!Files.isSymbolicLink(configured)) {
            "CLI conformance repository root cannot be a symlink: $configured"
        }
        require(Files.isDirectory(configured, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(configured)) {
            "CLI conformance repository root is not a readable directory: $configured"
        }
        try {
            configured.toRealPath()
        } catch (failure: IOException) {
            throw IllegalArgumentException("Cannot resolve CLI conformance repository root: $configured", failure)
        } catch (failure: SecurityException) {
            throw IllegalArgumentException("Cannot resolve CLI conformance repository root: $configured", failure)
        }
    }

    fun fixture(name: String): File = directory("conformance/cli-fixtures/$name")

    fun fixturesRoot(): File = directory("conformance/cli-fixtures")

    fun repositoryFile(relative: String): File {
        val file = resolve(relative)
        check(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(file)) {
            "CLI conformance repository file is not readable: $file"
        }
        return containedRealPath(file).toFile()
    }

    private fun directory(relative: String): File {
        val directory = resolve(relative)
        check(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(directory)) {
            "CLI conformance repository directory is not readable: $directory"
        }
        return containedRealPath(directory).toFile()
    }

    private fun resolve(relative: String): Path {
        val path = Path.of(relative)
        require(!path.isAbsolute && path.none { it.toString() == ".." }) { "Repository path must be relative" }
        val resolved = root.resolve(path).normalize()
        require(resolved.startsWith(root)) { "Repository path escapes the configured root" }
        var current = root
        path.forEach { segment ->
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) { "Repository path cannot contain symlinks: $current" }
        }
        return resolved
    }

    private fun containedRealPath(path: Path): Path {
        val real = try {
            path.toRealPath()
        } catch (failure: IOException) {
            throw IllegalStateException("Cannot resolve CLI conformance repository path: $path", failure)
        } catch (failure: SecurityException) {
            throw IllegalStateException("Cannot resolve CLI conformance repository path: $path", failure)
        }
        check(real.startsWith(root)) { "CLI conformance repository path escapes the configured root: $path" }
        return real
    }

    companion object {
        val configured = CliConformanceRepository(
            File(checkNotNull(System.getProperty(REPOSITORY_ROOT_PROPERTY))),
        )

        private const val REPOSITORY_ROOT_PROPERTY = "affected.test.repositoryRoot"
    }
}
