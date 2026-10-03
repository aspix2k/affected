package com.aspix2k.affected.impact

import com.aspix2k.affected.build.gradle.sourcePackageName
import java.net.URI
import java.nio.file.Path
import java.time.Instant

data class PromotedMap(val map: CompleteDependencyMap, val promotedAt: Instant)

data class StoredMaps(val maps: List<PromotedMap>, val unreadable: Int)

data class PromotedMaps(
    val system: String,
    val stored: StoredMaps,
    val currentCollectorVersion: String?,
)

enum class MapFreshness(val id: String) {
    FRESH("fresh"),
    COLLECTOR_MISMATCH("collector-mismatch"),
    COLLECTOR_UNVERIFIED("collector-unverified"),
    SOURCE_NEWER("source-newer"),
}

data class MapReport(
    val system: String,
    val taskKey: String,
    val promotedAt: Instant,
    val freshness: MapFreshness,
)

data class CoveringTest(val system: String, val taskKey: String, val testClass: String)

data class CoveringTestsResult(
    val classes: Set<String>,
    val recorded: List<CoveringTest>,
    val unknown: List<CoveringTest>,
    val maps: List<MapReport>,
    val unreadableMaps: Int,
    val reason: String?,
)

data class RunTarget(val root: String, val task: String)

object SourceClassNames {

    fun of(fileName: String, text: String): Set<String>? {
        val simple = fileName.substringBeforeLast('.')
        val prefix = sourcePackageName(text).let { if (it.isEmpty()) "" else "$it." }
        return when (fileName.substringAfterLast('.', "")) {
            "java" -> setOf(prefix + simple)
            "kt" -> setOf(prefix + simple, prefix + simple + FACADE_SUFFIX)
            else -> null
        }
    }

    private const val FACADE_SUFFIX = "Kt"
}

object CoveringTestsFinder {

    fun find(
        sources: List<PromotedMaps>,
        classNames: Set<String>,
        sourceModifiedAt: Instant,
    ): CoveringTestsResult {
        val unreadable = sources.sumOf { it.stored.unreadable }
        val all = sources.flatMap { source -> source.stored.maps.map { source to it } }
        if (all.isEmpty()) return empty(unreadable, if (unreadable > 0) "map-unreadable" else "no-map")

        val matched = all.mapNotNull { (source, promoted) ->
            val classes = promoted.map.artifacts.map { it.id.className }.filter { it.isCoveredBy(classNames) }.toSet()
            classes.takeIf(Set<String>::isNotEmpty)
                ?.let { Match(source, promoted, it, freshness(source, promoted, sourceModifiedAt)) }
        }
        if (matched.isEmpty()) return empty(unreadable, "file-not-in-map")

        val reports = matched
            .map {
                MapReport(it.source.system, it.promoted.map.identity.taskKey, it.promoted.promotedAt, it.freshness)
            }
            .sortedWith(compareBy({ it.system }, { it.taskKey }))
        val fresh = matched.filter { it.freshness == MapFreshness.FRESH }
        val recorded = fresh.flatMap { match ->
            match.promoted.map.records
                .filter { record -> record.dependencies.any { it.id.className in match.classes } }
                .map { it.toTest(match) }
        }
        val unknown = fresh.flatMap { match ->
            match.promoted.map.records.filter { it.unknownDependencies }.map { it.toTest(match) }
        }
        return CoveringTestsResult(
            classes = matched.flatMapTo(sortedSetOf<String>()) { it.classes },
            recorded = recorded.sortedWith(TEST_ORDER),
            unknown = unknown.sortedWith(TEST_ORDER),
            maps = reports,
            unreadableMaps = unreadable,
            reason = when {
                fresh.isEmpty() -> "stale-map"
                recorded.isEmpty() && unknown.isEmpty() -> "no-covering-test"
                else -> null
            },
        )
    }

    fun runTarget(taskKey: String): RunTarget? = runCatching {
        val separator = taskKey.lastIndexOf('|')
        require(separator > 0 && separator < taskKey.length - 1)
        RunTarget(Path.of(URI(taskKey.substring(0, separator))).toString(), taskKey.substring(separator + 1))
    }.getOrNull()

    fun command(system: String, task: String, testClasses: List<String>): String? {
        val tests = testClasses.filter(TEST_CLASS_NAME::matches).distinct()
        if (tests.isEmpty()) return null
        return when (system) {
            GRADLE -> tests.joinToString(" ", prefix = "./gradlew $task ") { "--tests '$it'" }
            MAVEN -> if (task == FAILSAFE_TASK) {
                "mvn verify -Dit.test='${tests.joinToString(",")}'"
            } else {
                "mvn $task -Dtest='${tests.joinToString(",")}'"
            }
            else -> null
        }
    }

    const val GRADLE = "gradle"
    const val MAVEN = "maven"

    private fun freshness(
        source: PromotedMaps,
        promoted: PromotedMap,
        sourceModifiedAt: Instant,
    ): MapFreshness = when {
        source.currentCollectorVersion == null -> MapFreshness.COLLECTOR_UNVERIFIED
        promoted.map.identity.collectorVersion != source.currentCollectorVersion -> MapFreshness.COLLECTOR_MISMATCH
        sourceModifiedAt.isAfter(promoted.promotedAt) -> MapFreshness.SOURCE_NEWER
        else -> MapFreshness.FRESH
    }

    private fun TestDependencyRecord.toTest(match: Match) =
        CoveringTest(match.source.system, match.promoted.map.identity.taskKey, testClass.value)

    private fun String.isCoveredBy(names: Set<String>): Boolean =
        this in names || names.any { startsWith("$it$") }

    private fun empty(unreadable: Int, reason: String) = CoveringTestsResult(
        classes = emptySet(),
        recorded = emptyList(),
        unknown = emptyList(),
        maps = emptyList(),
        unreadableMaps = unreadable,
        reason = reason,
    )

    private data class Match(
        val source: PromotedMaps,
        val promoted: PromotedMap,
        val classes: Set<String>,
        val freshness: MapFreshness,
    )

    private val TEST_ORDER = compareBy<CoveringTest>({ it.system }, { it.taskKey }, { it.testClass })
    private val TEST_CLASS_NAME = Regex("[A-Za-z0-9_.$]+")
    private const val FAILSAFE_TASK = "integration-test"
}
