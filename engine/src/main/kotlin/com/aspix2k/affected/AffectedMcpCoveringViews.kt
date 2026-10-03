package com.aspix2k.affected

import com.aspix2k.affected.impact.CoveringTest
import com.aspix2k.affected.impact.CoveringTestsFinder
import com.aspix2k.affected.impact.CoveringTestsResult

private const val COVERING_LIMITS = "Classes are matched by file name, so other top-level classes in the same file, " +
    "files with a custom JvmName and generated sources are not matched."

object AffectedMcpCoveringViews {

    fun tests(file: String, result: CoveringTestsResult?): AffectedMcpView {
        if (result == null) {
            return AffectedMcpView(
                text = "The file could not be read.",
                data = mapOf("file" to file, "reason" to "unreadable-file"),
                error = true,
            )
        }
        val reason = result.reason
        return AffectedMcpView(
            text = coveringText(file, result),
            data = mapOf(
                "file" to file,
                "classes" to result.classes.toList(),
                "modules" to coveringGroups(result.recorded),
                "dependsOnEverything" to coveringGroups(result.unknown),
                "maps" to result.maps.map { map ->
                    mapOf(
                        "system" to map.system,
                        "task" to CoveringTestsFinder.runTarget(map.taskKey)?.task,
                        "root" to CoveringTestsFinder.runTarget(map.taskKey)?.root,
                        "promotedAt" to map.promotedAt.toString(),
                        "freshness" to map.freshness.id,
                    )
                },
                "unreadableMaps" to result.unreadableMaps,
                "limits" to COVERING_LIMITS,
            ) + listOfNotNull(reason?.let { "reason" to it }),
            error = reason != null && reason != "no-covering-test",
        )
    }

    private fun coveringText(file: String, result: CoveringTestsResult): String = when (result.reason) {
        "no-map" -> "No dependency map exists yet. Run the affected tests once so the collector can record one."
        "map-unreadable" -> "The stored dependency maps are unreadable. Run the affected tests once to record new ones."
        "file-not-in-map" ->
            "No recorded class matches $file. It may be outside a Gradle or Maven JVM module, or its classes " +
                "are not named after the file. $COVERING_LIMITS"
        "stale-map" ->
            "Every map that knows $file is stale (${result.maps.joinToString { it.freshness.id }}), so it is not " +
                "trusted. Run the affected tests once to refresh it."
        "no-covering-test" ->
            "No fresh map records a test touching ${result.classes.joinToString()}. $COVERING_LIMITS"
        else -> "Tests covering $file: ${result.recorded.size} recorded, ${result.unknown.size} depending on " +
            "everything. $COVERING_LIMITS"
    }

    private fun coveringGroups(tests: List<CoveringTest>): List<Map<String, Any?>> =
        tests.groupBy { it.system to it.taskKey }.map { (key, group) ->
            val target = CoveringTestsFinder.runTarget(key.second)
            val names = group.map(CoveringTest::testClass)
            mapOf(
                "system" to key.first,
                "root" to target?.root,
                "task" to target?.task,
                "tests" to names,
                "command" to target?.let { CoveringTestsFinder.command(key.first, it.task, names) },
            )
        }
}
