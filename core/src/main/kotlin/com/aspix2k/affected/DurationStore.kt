package com.aspix2k.affected

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class DurationStore(private val file: Path) {

    fun read(): List<RecordedDuration> = runCatching {
        if (Files.isRegularFile(file)) {
            Files.readAllLines(file, StandardCharsets.UTF_8).mapNotNull(::parse)
        } else {
            emptyList()
        }
    }.getOrElse {
        LOG.warn("Could not read recorded durations", it)
        emptyList()
    }

    fun record(entries: List<RecordedDuration>): List<RecordedDuration> {
        val fresh = entries.filter { it.tasks.size in 1..MAX_TASKS && it.millis >= 0 }
        if (fresh.isEmpty()) return read()
        val merged = (fresh + read()).distinctBy(RecordedDuration::identity)
            .sortedByDescending(RecordedDuration::recordedAt)
            .take(MAX_ENTRIES)
        runCatching { write(merged) }.onFailure { LOG.warn("Could not record durations", it) }
        return merged
    }

    private fun write(entries: List<RecordedDuration>) {
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, FILE_NAME, ".tmp")
        try {
            Files.write(temporary, entries.map(::format), StandardCharsets.UTF_8)
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun format(entry: RecordedDuration): String =
        (listOf(entry.systemId, entry.root, entry.millis.toString(), entry.recordedAt.toString()) + entry.tasks)
            .joinToString("\t") { URLEncoder.encode(it, StandardCharsets.UTF_8) }

    private fun parse(line: String): RecordedDuration? = runCatching {
        val fields = line.split('\t').map { URLDecoder.decode(it, StandardCharsets.UTF_8) }
        RecordedDuration(fields[0], fields[1], fields.drop(FIXED_FIELDS), fields[2].toLong(), fields[3].toLong())
    }.getOrNull()?.takeIf { it.tasks.isNotEmpty() && it.tasks.size <= MAX_TASKS && it.millis >= 0 }

    companion object {
        internal const val MAX_ENTRIES = 256
        internal const val MAX_TASKS = 512
        private const val FILE_NAME = "durations"
        private const val FIXED_FIELDS = 4
        private val LOG = logger<DurationStore>()

        fun forProject(project: Project): DurationStore = DurationStore(
            PathManager.getSystemDir().resolve("affected").resolve(project.locationHash).resolve("durations.tsv"),
        )
    }
}
