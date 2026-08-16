package com.aspix2k.affected.build

import com.aspix2k.affected.impact.CollectorMapReader
import com.aspix2k.affected.impact.DependencyMapPromotion
import com.aspix2k.affected.impact.DependencyMapStore
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class CollectorRun private constructor(
    private val cacheRoot: Path,
    val artifacts: List<Path>,
    val outputRoot: Path,
    val collectorVersion: String,
    private val runId: String,
) {
    private val cancelled = AtomicBoolean()
    val mapsRoot: Path = cacheRoot.resolve(MAPS_DIRECTORY)

    fun cancel() {
        cancelled.set(true)
    }

    fun complete(passed: Boolean) {
        try {
            if (passed && !cancelled.get()) promote()
        } finally {
            deleteTree(outputRoot)
        }
    }

    private fun promote() {
        val taskDirectories = Files.list(outputRoot).use { stream ->
            stream.filter { it.fileName.toString() != SELECTION_DIAGNOSTICS_MARKER }
                .limit(MAX_TASKS + 1L)
                .toList()
                .also { require(it.size <= MAX_TASKS) }
        }
        val store = DependencyMapStore(mapsRoot)
        taskDirectories.forEach { directory ->
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
                return@forEach
            }
            val candidate = CollectorMapReader.read(directory, collectorVersion, runId) ?: return@forEach
            val previous = store.read(candidate.identity.taskKey)
            val promoted = DependencyMapPromotion.promote(previous, candidate)
            if (promoted != null && promoted != previous) store.write(promoted)
        }
    }

    internal companion object {
        fun create(cacheRoot: Path, requestedArtifacts: List<Path>): CollectorRun? = runCatching {
            require(requestedArtifacts.isNotEmpty())
            val root = secureDirectory(cacheRoot)
            val artifacts = requestedArtifacts.map { it.secureFile() }
            cleanupRuns(root.resolve(RUNS_DIRECTORY))
            val runs = secureDirectory(root.resolve(RUNS_DIRECTORY))
            val runId = UUID.randomUUID().toString()
            val output = Files.createDirectory(runs.resolve(runId)).toRealPath(LinkOption.NOFOLLOW_LINKS)
            CollectorRun(root, artifacts, output, artifacts.version(), runId)
        }.getOrNull()

        private fun List<Path>.version(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            forEach(digest::update)
            return digest.digest().toHex()
        }

        private fun Path.secureFile(): Path {
            val absolute = toAbsolutePath().normalize()
            require(Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(absolute))
            return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS)
        }

        private fun cleanupRuns(path: Path) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            val root = secureDirectory(path)
            val cutoff = Instant.now().minus(STALE_RUN_AGE)
            Files.list(root).use { stream ->
                stream.limit(MAX_STALE_RUNS + 1L).toList().also { require(it.size <= MAX_STALE_RUNS) }
            }.filter { candidate ->
                Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(candidate) &&
                    Files.getLastModifiedTime(candidate, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)
            }.forEach(::deleteTree)
        }
    }
}

private fun MessageDigest.update(path: Path) {
    path.toFile().inputStream().use { input ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) update(buffer, 0, read)
        }
    }
}

private fun secureDirectory(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    Files.createDirectories(absolute)
    require(!Files.isSymbolicLink(absolute))
    val real = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS)
    require(Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(real) && Files.isWritable(real))
    return real
}

private fun deleteTree(path: Path) {
    val absolute = path.toAbsolutePath().normalize()
    if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) return
    require(absolute.nameCount >= MINIMUM_DELETE_DEPTH)
    Files.walkFileTree(
        absolute,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, failure: java.io.IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private const val RUNS_DIRECTORY = "runs"
internal const val MAPS_DIRECTORY = "maps"
private const val MAX_TASKS = 10_000
private const val SELECTION_DIAGNOSTICS_MARKER = "selection-diagnostics.reported"
private const val MAX_STALE_RUNS = 1_000
private const val BUFFER_SIZE = 64 * 1024
private const val MINIMUM_DELETE_DEPTH = 4
private val STALE_RUN_AGE: Duration = Duration.ofDays(7)
