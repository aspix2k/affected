package com.aspix2k.affected.build

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

internal object ManifestSearch {

    fun find(root: File, name: String, limit: Int = MAX_MATCHES): List<File> =
        find(root, limit) { it.name == name }

    fun findByExtension(root: File, extension: String, limit: Int = MAX_MATCHES): List<File> =
        find(root, limit) { it.extension.equals(extension, ignoreCase = true) }

    fun fingerprint(root: File, files: List<File>): String? = runCatching {
        if (files.size > MAX_FINGERPRINT_FILES) return null
        val realRoot = root.toPath().toRealPath()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        files.distinctBy { it.absoluteFile.normalize().path }.sortedBy { it.invariantSeparatorsPath }.forEach { file ->
            val requested = file.toPath().toAbsolutePath().normalize()
            if (Files.isSymbolicLink(requested) ||
                !Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS) ||
                !Files.isReadable(requested)
            ) {
                return null
            }
            val real = requested.toRealPath()
            if (!real.startsWith(realRoot)) return null
            val size = Files.size(real)
            total += size
            if (size > MAX_MANIFEST_BYTES || total > MAX_TOTAL_BYTES) return null
            digest.update(realRoot.relativize(real).toString().replace('\\', '/').toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            Files.newInputStream(real).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.update(0.toByte())
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }.getOrNull()

    fun readText(file: File): String? = runCatching {
        val path = file.toPath().toAbsolutePath().normalize()
        if (!isReadableManifest(path)) return null
        Files.readString(path, StandardCharsets.UTF_8)
    }.getOrNull()

    fun anyFile(root: File, matches: (File) -> Boolean): Boolean? {
        val queue = ArrayDeque<Pair<File, Int>>()
        queue += root to 0
        var visited = 0
        while (queue.isNotEmpty()) {
            if (visited++ >= MAX_DIRECTORIES) return null
            val (directory, depth) = queue.removeFirst()
            when (scanAny(directory, depth, matches, queue)) {
                true -> return true
                null -> return null
                false -> Unit
            }
        }
        return false
    }

    fun layoutFingerprint(
        root: File,
        maxDepth: Int = MAX_DEPTH,
        matches: (File) -> Boolean,
    ): String? = runCatching {
        val realRoot = root.toPath().toRealPath()
        val markers = collectLayoutMarkers(root, realRoot, maxDepth, matches) ?: return null
        hashMarkers(markers)
    }.getOrNull()

    private fun collectLayoutMarkers(
        root: File,
        realRoot: java.nio.file.Path,
        maxDepth: Int,
        matches: (File) -> Boolean,
    ): List<String>? {
        if (maxDepth < 0) return null
        val markers = ArrayList<String>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue += root to 0
        var visited = 0
        while (queue.isNotEmpty()) {
            if (visited++ >= MAX_DIRECTORIES) return null
            val (directory, depth) = queue.removeFirst()
            if (!scanLayout(directory, depth, realRoot, maxDepth, matches, markers, queue)) return null
        }
        return markers
    }

    private fun scanAny(
        directory: File,
        depth: Int,
        matches: (File) -> Boolean,
        queue: ArrayDeque<Pair<File, Int>>,
    ): Boolean? {
        val children = directory.listFiles() ?: return null
        if (children.size > MAX_DIRECTORIES) return null
        for (child in children) {
            if (unsafeTraversalSymlink(child, depth, MAX_DEPTH, matches)) return null
            if (Files.isRegularFile(child.toPath(), LinkOption.NOFOLLOW_LINKS) && matches(child)) return true
            if (canEnter(child, depth)) {
                if (queue.size >= MAX_DIRECTORIES) return null
                queue += child to depth + 1
            }
        }
        return false
    }

    private fun find(root: File, limit: Int, matches: (File) -> Boolean): List<File> {
        if (limit <= 0) return emptyList()
        val found = ArrayList<File>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue += root to 0
        var visited = 0

        while (queue.isNotEmpty()) {
            if (visited++ >= MAX_DIRECTORIES) return emptyList()
            val (directory, depth) = queue.removeFirst()
            if (!scan(directory, depth, limit, matches, found, queue)) return emptyList()
            if (found.size >= limit) return emptyList()
        }
        return found
    }

    private fun scan(
        directory: File,
        depth: Int,
        limit: Int,
        matches: (File) -> Boolean,
        found: MutableList<File>,
        queue: ArrayDeque<Pair<File, Int>>,
    ): Boolean {
        val children = directory.listFiles() ?: return false
        if (children.size > MAX_DIRECTORIES) return false

        for (child in children) {
            if (found.size >= limit) return true
            if (unsafeTraversalSymlink(child, depth, MAX_DEPTH, matches)) return false
            when {
                Files.isRegularFile(child.toPath(), LinkOption.NOFOLLOW_LINKS) && matches(child) -> found += child
                canEnter(child, depth) -> {
                    if (queue.size >= MAX_DIRECTORIES) return false
                    queue += child to depth + 1
                }
            }
        }
        return true
    }

    private const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L
    private const val MAX_MATCHES = 4097
}

private fun scanLayout(
    directory: File,
    depth: Int,
    realRoot: java.nio.file.Path,
    maxDepth: Int,
    matches: (File) -> Boolean,
    markers: MutableList<String>,
    queue: ArrayDeque<Pair<File, Int>>,
): Boolean {
    val children = directory.listFiles() ?: return false
    if (children.size > MAX_DIRECTORIES) return false
    for (child in children) {
        if (!addLayoutMarker(child, depth, maxDepth, realRoot, matches, markers)) return false
        if (canEnter(child, depth, maxDepth)) {
            if (queue.size >= MAX_DIRECTORIES) return false
            queue += child to depth + 1
        }
    }
    return true
}

private fun canEnter(file: File, depth: Int, maxDepth: Int = MAX_DEPTH): Boolean =
    depth < maxDepth &&
        Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
        !Files.isSymbolicLink(file.toPath()) &&
        file.name !in SKIPPED_DIRECTORIES &&
        !file.name.startsWith(".")

private fun addLayoutMarker(
    child: File,
    depth: Int,
    maxDepth: Int,
    realRoot: java.nio.file.Path,
    matches: (File) -> Boolean,
    markers: MutableList<String>,
): Boolean {
    val path = child.toPath()
    if (Files.isSymbolicLink(path)) return !unsafeTraversalSymlink(child, depth, maxDepth, matches)
    val isDirectory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
    val isFile = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
    if ((!isDirectory && !isFile) || !matches(child)) return true
    if (markers.size >= MAX_FINGERPRINT_FILES) return false
    val real = path.toRealPath()
    if (!real.startsWith(realRoot)) return false
    markers += "${if (isDirectory) 'd' else 'f'}:${realRoot.relativize(real)}"
    return true
}

private fun hashMarkers(markers: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    markers.sorted().forEach { marker ->
        digest.update(marker.replace('\\', '/').toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private const val MAX_FINGERPRINT_FILES = 4096
private const val MAX_DIRECTORIES = 16_384
private const val MAX_DEPTH = 7
private const val MAX_MANIFEST_BYTES = 8L * 1024L * 1024L

private fun isReadableManifest(path: java.nio.file.Path): Boolean =
    !Files.isSymbolicLink(path) &&
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
        Files.isReadable(path) &&
        Files.size(path) <= MAX_MANIFEST_BYTES

private fun unsafeTraversalSymlink(
    file: File,
    depth: Int,
    maxDepth: Int,
    matches: (File) -> Boolean,
): Boolean {
    if (!Files.isSymbolicLink(file.toPath())) return false
    if (matches(file)) return true
    return depth < maxDepth &&
        Files.isDirectory(file.toPath()) &&
        file.name !in SKIPPED_DIRECTORIES &&
        !file.name.startsWith('.')
}

private val SKIPPED_DIRECTORIES = setOf(
    ".git", ".gradle", ".idea", ".venv", "venv", "node_modules", "vendor",
    "build", "out", "target", "bin", "obj", "dist", "coverage",
    "DerivedData", "Pods", "__pycache__", ".tox", ".cache", "fixtures",
    "cmake-build-debug", "cmake-build-release",
)
