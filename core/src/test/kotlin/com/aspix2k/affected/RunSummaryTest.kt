package com.aspix2k.affected

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunSummaryTest {

    private val inventory = TestInventory(
        modules = 4,
        tasks = setOf(":a:test", ":b:test", ":c:test", ":d:test").mapTo(HashSet()) { TaskKey("GRADLE", "/repo", it) },
    )

    private fun plan(vararg tasks: String) =
        Plan(listOf(TaskGroup("GRADLE", "/repo", tasks.toList())), tested = tasks.size, compiled = 0)

    private fun recorded(millis: Long, at: Long, vararg tasks: String) =
        RecordedDuration("GRADLE", "/repo", tasks.toList().sorted(), millis, at)

    @Test
    fun `counts come from the plan and the inventory`() {
        val summary = summarizeRun(plan(":a:test"), inventory, 48_000, emptyList())

        assertEquals(1, summary.modulesTested)
        assertEquals(4, summary.modulesWithTests)
        assertEquals(1, summary.tasks)
        assertEquals(48_000, summary.durationMillis)
    }

    @Test
    fun `without recorded durations there is no estimate and every skipped task is unknown`() {
        val summary = summarizeRun(plan(":a:test"), inventory, 1_000, emptyList())

        assertNull(summary.estimatedSavedMillis)
        assertEquals(3, summary.skippedWithoutEstimate)
    }

    @Test
    fun `only groups whose tasks were all skipped add to the estimate`() {
        val summary = summarizeRun(
            plan(":a:test"),
            inventory,
            1_000,
            listOf(recorded(60_000, 1, ":b:test"), recorded(500_000, 2, ":a:test", ":c:test")),
        )

        assertEquals(60_000, summary.estimatedSavedMillis)
        assertEquals(2, summary.skippedWithoutEstimate)
    }

    @Test
    fun `overlapping records are counted once and the newest wins`() {
        val summary = summarizeRun(
            plan(":a:test"),
            inventory,
            1_000,
            listOf(
                recorded(90_000, 1, ":b:test", ":c:test"),
                recorded(10_000, 2, ":b:test"),
                recorded(20_000, 3, ":d:test"),
            ),
        )

        assertEquals(30_000, summary.estimatedSavedMillis)
        assertEquals(1, summary.skippedWithoutEstimate)
    }

    @Test
    fun `a run that skipped nothing has no estimate and no unknowns`() {
        val summary = summarizeRun(
            plan(":a:test", ":b:test", ":c:test", ":d:test"),
            inventory,
            1_000,
            listOf(recorded(5_000, 1, ":a:test")),
        )

        assertNull(summary.estimatedSavedMillis)
        assertEquals(0, summary.skippedWithoutEstimate)
    }

    @Test
    fun `durations are compact and locale neutral`() {
        assertEquals("1 s", formatDuration(0))
        assertEquals("1 s", formatDuration(400))
        assertEquals("48 s", formatDuration(48_000))
        assertEquals("11 min", formatDuration(11 * 60_000L + 20_000))
        assertEquals("2 h", formatDuration(120 * 60_000L))
        assertEquals("1 h 5 min", formatDuration(65 * 60_000L))
    }

    @Test
    fun `the store keeps the newest entry per group and survives a round trip`() {
        val file = Files.createTempDirectory("durations").resolve("nested").resolve("durations.tsv")
        val store = DurationStore(file)

        store.record(listOf(recorded(1_000, 1, ":a:test", ":b:test")))
        val merged = store.record(listOf(recorded(2_000, 2, ":b:test", ":a:test"), recorded(3_000, 3, ":c:test")))

        assertEquals(listOf(3_000L, 2_000L), merged.map { it.millis })
        assertEquals(merged, DurationStore(file).read())
    }

    @Test
    fun `the store is bounded and drops the oldest entries`() {
        val store = DurationStore(Files.createTempDirectory("durations").resolve("durations.tsv"))
        val entries = (1..DurationStore.MAX_ENTRIES + 10).map { recorded(it.toLong(), it.toLong(), ":m$it:test") }

        val merged = store.record(entries)

        assertEquals(DurationStore.MAX_ENTRIES, merged.size)
        assertEquals(11L, merged.minOf { it.recordedAt })
        assertEquals(DurationStore.MAX_ENTRIES, store.read().size)
    }

    @Test
    fun `oversized groups and unreadable lines are ignored`() {
        val file = Files.createTempDirectory("durations").resolve("durations.tsv")
        val store = DurationStore(file)
        val oversized = recorded(1, 1, *Array(DurationStore.MAX_TASKS + 1) { ":m$it:test" })
        Files.write(file, listOf("garbage", "GRADLE\t%2Frepo\tx\t1\t%3Aa%3Atest"))

        assertEquals(emptyList(), store.read())
        assertEquals(emptyList(), store.record(listOf(oversized)))
    }

    @Test
    fun `special characters in roots and tasks survive the file format`() {
        val store = DurationStore(Files.createTempDirectory("durations").resolve("durations.tsv"))
        val entry = RecordedDuration("NODE", "/repo/a b\tc", listOf("x:y z", "ü"), 7, 9)

        store.record(listOf(entry))

        assertEquals(listOf(entry), store.read())
    }
}
