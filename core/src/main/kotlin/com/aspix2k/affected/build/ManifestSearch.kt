package com.aspix2k.affected.build

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal object ManifestSearch {

    fun find(
        root: File,
        name: String,
        limit: Int = PerformanceBudgets.MAX_MATCHES,
        budgetNanos: Long = PerformanceBudgets.SCAN_TIME_NS,
    ): List<File> =
        find(root, limit, budgetNanos) { it.name == name }

    fun findByExtension(
        root: File,
        extension: String,
        limit: Int = PerformanceBudgets.MAX_MATCHES,
        budgetNanos: Long = PerformanceBudgets.SCAN_TIME_NS,
    ): List<File> =
        find(root, limit, budgetNanos) { it.extension.equals(extension, ignoreCase = true) }

    fun completeFiles(
        root: File,
        budgetNanos: Long = PerformanceBudgets.SCAN_TIME_NS,
        afterScan: (List<File>) -> Boolean = { true },
        matches: (File) -> Boolean,
    ): List<File>? =
        findComplete(
            root,
            PerformanceBudgets.MAX_MATCHES,
            budgetNanos,
            true,
            afterScan,
            matches,
        )

    fun fingerprint(root: File, files: List<File>): String? = runCatching {
        if (files.size > PerformanceBudgets.MAX_FINGERPRINT_FILES) return null
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
            if (size > PerformanceBudgets.MAX_MANIFEST_BYTES || total > PerformanceBudgets.MAX_TOTAL_BYTES) return null
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

    fun anyFile(
        root: File,
        excludedRoots: Set<java.nio.file.Path> = emptySet(),
        matches: (File) -> Boolean,
    ): Boolean? {
        val queue = ArrayDeque<Pair<File, Int>>()
        queue += root to 0
        var visited = 0
        while (queue.isNotEmpty()) {
            if (visited++ >= PerformanceBudgets.MAX_DIRECTORIES) return null
            val (directory, depth) = queue.removeFirst()
            when (scanAny(directory, depth, excludedRoots, matches, queue)) {
                true -> return true
                null -> return null
                false -> Unit
            }
        }
        return false
    }

    fun layoutFingerprint(
        root: File,
        maxDepth: Int = PerformanceBudgets.MAX_DEPTH,
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
            if (visited++ >= PerformanceBudgets.MAX_DIRECTORIES) return null
            val (directory, depth) = queue.removeFirst()
            if (!scanLayout(directory, depth, realRoot, maxDepth, matches, markers, queue)) return null
        }
        return markers
    }

    private fun scanAny(
        directory: File,
        depth: Int,
        excludedRoots: Set<java.nio.file.Path>,
        matches: (File) -> Boolean,
        queue: ArrayDeque<Pair<File, Int>>,
    ): Boolean? {
        val children = directory.listFiles() ?: return null
        if (children.size > PerformanceBudgets.MAX_DIRECTORIES) return null
        for (child in children) {
            if (child.toPath().toAbsolutePath().normalize() in excludedRoots) continue
            if (unsafeTraversalSymlink(child, depth, PerformanceBudgets.MAX_DEPTH, matches)) return null
            if (Files.isRegularFile(child.toPath(), LinkOption.NOFOLLOW_LINKS) && matches(child)) return true
            if (canEnter(child, depth)) {
                if (queue.size >= PerformanceBudgets.MAX_DIRECTORIES) return null
                queue += child to depth + 1
            }
        }
        return false
    }

    private fun find(
        root: File,
        limit: Int,
        budgetNanos: Long,
        matches: (File) -> Boolean,
    ): List<File> = findComplete(root, limit, budgetNanos, false, { true }, matches).orEmpty()
}

private fun findComplete(
    root: File,
    limit: Int,
    budgetNanos: Long,
    requireCompleteDepth: Boolean,
    afterScan: (List<File>) -> Boolean,
    matches: (File) -> Boolean,
): List<File>? {
    if (limit <= 0 || budgetNanos <= 0) return null
    val (requestedRoot, realRoot) = completeSearchRoots(root) ?: return null
    val started = System.nanoTime()
    val scan = scanCompleteTree(
        requestedRoot,
        realRoot,
        limit,
        budgetNanos,
        requireCompleteDepth,
        started,
        matches,
    ) ?: return null
    val fileFingerprint = if (requireCompleteDepth) {
        completeFilesFingerprint(scan.files, realRoot, started, budgetNanos) ?: return null
    } else {
        null
    }
    if (!afterScan(scan.files)) return null
    if (!completeDirectoriesCurrent(scan.directories, realRoot, started, budgetNanos)) return null
    if (
        fileFingerprint != null &&
        fileFingerprint != completeFilesFingerprint(scan.files, realRoot, started, budgetNanos)
    ) {
        return null
    }
    return scan.files
}

private fun scanCompleteTree(
    requestedRoot: java.nio.file.Path,
    realRoot: java.nio.file.Path,
    limit: Int,
    budgetNanos: Long,
    requireCompleteDepth: Boolean,
    started: Long,
    matches: (File) -> Boolean,
): CompleteScanResult? {
    val found = ArrayList<File>()
    val queue = ArrayDeque<Pair<File, Int>>()
    queue += requestedRoot.toFile() to 0
    val completeRoot = realRoot.takeIf { requireCompleteDepth }
    val visitedDirectories = LinkedHashMap<File, CompleteDirectoryIdentity>()
    var visited = 0

    while (queue.isNotEmpty()) {
        if (System.nanoTime() - started >= budgetNanos) return null
        if (visited++ >= PerformanceBudgets.MAX_DIRECTORIES) return null
        val (directory, depth) = queue.removeFirst()
        val before = completeRoot?.let { completeDirectoryIdentity(directory, it) ?: return null }
        if (!scanComplete(directory, depth, limit, completeRoot, matches, found, queue)) return null
        if (before != null) {
            if (before != completeDirectoryIdentity(directory, requireNotNull(completeRoot))) return null
            visitedDirectories[directory] = before
        }
        if (found.size >= limit) return null
    }
    return CompleteScanResult(found, visitedDirectories)
}

private fun completeSearchRoots(root: File): Pair<java.nio.file.Path, java.nio.file.Path>? {
    val requested = root.toPath().toAbsolutePath().normalize()
    if (
        Files.isSymbolicLink(requested) ||
        !Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS) ||
        !Files.isReadable(requested)
    ) {
        return null
    }
    val real = runCatching { requested.toRealPath() }.getOrNull() ?: return null
    return requested to real
}

private fun completeDirectoriesCurrent(
    directories: Map<File, CompleteDirectoryIdentity>,
    realRoot: java.nio.file.Path,
    started: Long,
    budgetNanos: Long,
): Boolean {
    for ((directory, identity) in directories) {
        if (System.nanoTime() - started >= budgetNanos) return false
        if (identity != completeDirectoryIdentity(directory, realRoot)) return false
    }
    return true
}

private fun completeFilesFingerprint(
    files: List<File>,
    realRoot: java.nio.file.Path,
    started: Long,
    budgetNanos: Long,
): String? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    var totalBytes = 0L
    for (file in files.sortedBy { it.invariantSeparatorsPath }) {
        if (System.nanoTime() - started >= budgetNanos || Thread.currentThread().isInterrupted) return null
        val size = updateCompleteFileFingerprint(
            file,
            realRoot,
            PerformanceBudgets.MAX_TOTAL_BYTES - totalBytes,
            started,
            budgetNanos,
            digest,
        ) ?: return null
        totalBytes += size
    }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}.getOrNull()

private fun updateCompleteFileFingerprint(
    file: File,
    realRoot: java.nio.file.Path,
    remainingBytes: Long,
    started: Long,
    budgetNanos: Long,
    digest: MessageDigest,
): Long? = runCatching {
    val path = file.toPath().toAbsolutePath().normalize()
    val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    require(before.isRegularFile && !Files.isSymbolicLink(path) && Files.isReadable(path))
    require(before.size() <= PerformanceBudgets.MAX_MANIFEST_BYTES && before.size() <= remainingBytes)
    val real = path.toRealPath().also { require(it.startsWith(realRoot)) }
    digest.update(realRoot.relativize(real).toString().replace('\\', '/').toByteArray(StandardCharsets.UTF_8))
    digest.update(0.toByte())
    Files.newInputStream(real).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var hashedBytes = 0L
        while (true) {
            require(System.nanoTime() - started < budgetNanos && !Thread.currentThread().isInterrupted)
            val count = input.read(buffer)
            if (count < 0) break
            hashedBytes += count
            require(hashedBytes <= before.size())
            digest.update(buffer, 0, count)
        }
        require(hashedBytes == before.size())
    }
    val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    require(before.fileKey() == after.fileKey())
    require(before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime())
    digest.update(0.toByte())
    after.size()
}.getOrNull()

private fun scanComplete(
    directory: File,
    depth: Int,
    limit: Int,
    completeRoot: java.nio.file.Path?,
    matches: (File) -> Boolean,
    found: MutableList<File>,
    queue: ArrayDeque<Pair<File, Int>>,
): Boolean {
    val children = directory.listFiles() ?: return false
    if (children.size > PerformanceBudgets.MAX_DIRECTORIES) return false

    for (child in children) {
        if (found.size >= limit) break
        if (!processCompleteChild(child, depth, completeRoot, matches, found, queue)) return false
    }
    return true
}

private fun processCompleteChild(
    child: File,
    depth: Int,
    completeRoot: java.nio.file.Path?,
    matches: (File) -> Boolean,
    found: MutableList<File>,
    queue: ArrayDeque<Pair<File, Int>>,
): Boolean {
    if (!isCompleteChildSafe(child, depth, completeRoot, matches)) return false
    if (Files.isRegularFile(child.toPath(), LinkOption.NOFOLLOW_LINKS) && matches(child)) {
        found += child
        return true
    }
    if (completeRoot != null && depth >= PerformanceBudgets.MAX_DEPTH && isTraversableDirectory(child)) return false
    if (canEnter(child, depth)) {
        if (queue.size >= PerformanceBudgets.MAX_DIRECTORIES) return false
        queue += child to depth + 1
    }
    return true
}

private fun isCompleteChildSafe(
    child: File,
    depth: Int,
    completeRoot: java.nio.file.Path?,
    matches: (File) -> Boolean,
): Boolean =
    !unsafeTraversalSymlink(
        child,
        depth,
        PerformanceBudgets.MAX_DEPTH,
        matches,
        completeRoot != null,
    ) && (completeRoot == null || !unsafeCompleteDirectory(child, completeRoot))

private fun isTraversableDirectory(file: File): Boolean =
    Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
        !Files.isSymbolicLink(file.toPath()) &&
        file.name !in SKIPPED_DIRECTORIES &&
        !file.name.startsWith(".")

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
    if (children.size > PerformanceBudgets.MAX_DIRECTORIES) return false
    for (child in children) {
        if (!addLayoutMarker(child, depth, maxDepth, realRoot, matches, markers)) return false
        if (canEnter(child, depth, maxDepth)) {
            if (queue.size >= PerformanceBudgets.MAX_DIRECTORIES) return false
            queue += child to depth + 1
        }
    }
    return true
}

private fun canEnter(file: File, depth: Int, maxDepth: Int = PerformanceBudgets.MAX_DEPTH): Boolean =
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
    if (markers.size >= PerformanceBudgets.MAX_FINGERPRINT_FILES) return false
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

private fun isReadableManifest(path: java.nio.file.Path): Boolean =
    !Files.isSymbolicLink(path) &&
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
        Files.isReadable(path) &&
        Files.size(path) <= PerformanceBudgets.MAX_MANIFEST_BYTES

private fun unsafeTraversalSymlink(
    file: File,
    depth: Int,
    maxDepth: Int,
    matches: (File) -> Boolean,
    requireCompleteDepth: Boolean = false,
): Boolean {
    if (!Files.isSymbolicLink(file.toPath())) return false
    if (matches(file)) return true
    return (requireCompleteDepth || depth < maxDepth) &&
        Files.isDirectory(file.toPath()) &&
        file.name !in SKIPPED_DIRECTORIES &&
        !file.name.startsWith('.')
}

private fun unsafeCompleteDirectory(file: File, realRoot: java.nio.file.Path): Boolean {
    if (file.name in SKIPPED_DIRECTORIES || file.name.startsWith('.')) return false
    val path = file.toPath()
    if (!Files.isDirectory(path)) return false
    return completeDirectoryIdentity(file, realRoot) == null
}

private fun completeDirectoryIdentity(
    directory: File,
    realRoot: java.nio.file.Path,
): CompleteDirectoryIdentity? {
    val path = directory.toPath().toAbsolutePath().normalize()
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return null
    val attributes = runCatching {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }.getOrNull() ?: return null
    if (attributes.isOther) return null
    val real = runCatching { path.toRealPath() }.getOrNull()?.takeIf { it.startsWith(realRoot) } ?: return null
    return CompleteDirectoryIdentity(real, attributes.fileKey(), attributes.lastModifiedTime(), attributes.size())
}

private data class CompleteDirectoryIdentity(
    val realPath: java.nio.file.Path,
    val fileKey: Any?,
    val modifiedAt: java.nio.file.attribute.FileTime,
    val size: Long,
)

private data class CompleteScanResult(
    val files: List<File>,
    val directories: Map<File, CompleteDirectoryIdentity>,
)

private val SKIPPED_DIRECTORIES = setOf(
    ".git", ".gradle", ".idea", ".venv", "venv", "node_modules", "vendor",
    "build", "out", "target", "bin", "obj", "dist", "coverage",
    "DerivedData", "Pods", "__pycache__", ".tox", ".cache", "fixtures",
    "cmake-build-debug", "cmake-build-release",
)
