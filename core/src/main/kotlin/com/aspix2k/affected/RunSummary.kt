package com.aspix2k.affected

data class TaskKey(val systemId: String, val root: String, val task: String)

data class TestInventory(val modules: Int = 0, val tasks: Set<TaskKey> = emptySet())

data class RecordedDuration(
    val systemId: String,
    val root: String,
    val tasks: List<String>,
    val millis: Long,
    val recordedAt: Long,
) {
    val keys: Set<TaskKey> get() = tasks.mapTo(LinkedHashSet()) { TaskKey(systemId, root, it) }

    internal val identity: Triple<String, String, Set<String>> get() = Triple(systemId, root, tasks.toSet())
}

data class RunSummary(
    val modulesTested: Int,
    val modulesWithTests: Int,
    val tasks: Int,
    val durationMillis: Long,
    val estimatedSavedMillis: Long?,
    val skippedWithoutEstimate: Int,
)

internal fun TaskGroup.recordedDuration(millis: Long, recordedAt: Long): RecordedDuration =
    RecordedDuration(systemId, root, tasks.distinct().sorted(), millis, recordedAt)

internal fun summarizeRun(
    plan: Plan,
    inventory: TestInventory,
    durationMillis: Long,
    recorded: List<RecordedDuration>,
): RunSummary {
    val ran = plan.groups.flatMapTo(HashSet()) { group -> group.tasks.map { TaskKey(group.systemId, group.root, it) } }
    val skipped = inventory.tasks - ran
    val (remaining, savedMillis) = recorded.sortedByDescending(RecordedDuration::recordedAt)
        .fold(skipped to 0L) { (left, saved), entry ->
            val keys = entry.keys
            if (keys.isNotEmpty() && left.containsAll(keys)) (left - keys) to (saved + entry.millis) else left to saved
        }
    return RunSummary(
        modulesTested = plan.tested,
        modulesWithTests = inventory.modules,
        tasks = ran.size,
        durationMillis = durationMillis,
        estimatedSavedMillis = savedMillis.takeIf { remaining.size < skipped.size },
        skippedWithoutEstimate = remaining.size,
    )
}

fun formatDuration(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) + MILLIS_PER_SECOND / 2) / MILLIS_PER_SECOND
    val minutes = (seconds + SECONDS_PER_MINUTE / 2) / SECONDS_PER_MINUTE
    return when {
        seconds < SECONDS_PER_MINUTE -> "${seconds.coerceAtLeast(1)} s"
        minutes < MINUTES_PER_HOUR -> "$minutes min"
        minutes % MINUTES_PER_HOUR == 0L -> "${minutes / MINUTES_PER_HOUR} h"
        else -> "${minutes / MINUTES_PER_HOUR} h ${minutes % MINUTES_PER_HOUR} min"
    }
}

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
