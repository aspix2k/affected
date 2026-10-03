package com.aspix2k.affected

import com.aspix2k.affected.impact.CoveringTest
import com.aspix2k.affected.impact.CoveringTestsResult
import com.aspix2k.affected.impact.MapFreshness
import com.aspix2k.affected.impact.MapReport
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AffectedMcpCoveringViewsTest {

    private val taskKey = "file:///work/app/|:app:test"
    private val promotedAt = Instant.parse("2026-01-02T00:00:00Z")

    @Test
    fun `recorded and everything-dependent tests are grouped by module with run commands`() {
        val result = result(
            recorded = listOf(CoveringTest("gradle", taskKey, "a.RepoTest")),
            unknown = listOf(CoveringTest("gradle", taskKey, "a.AllTest")),
        )

        val view = AffectedMcpCoveringViews.tests("src/Repo.kt", result)

        assertFalse(view.error)
        val module = (view.data["modules"] as List<*>).single() as Map<*, *>
        assertEquals(":app:test", module["task"])
        assertEquals("/work/app", module["root"])
        assertEquals(listOf("a.RepoTest"), module["tests"])
        assertEquals("./gradlew :app:test --tests 'a.RepoTest'", module["command"])
        val everything = (view.data["dependsOnEverything"] as List<*>).single() as Map<*, *>
        assertEquals(listOf("a.AllTest"), everything["tests"])
        val map = (view.data["maps"] as List<*>).single() as Map<*, *>
        assertEquals("fresh", map["freshness"])
        assertEquals("2026-01-02T00:00:00Z", map["promotedAt"])
        assertFalse(view.data.containsKey("reason"))
        assertTrue((view.data["limits"] as String).contains("matched by file name"))
    }

    @Test
    fun `every unknown outcome is an error with a short reason and no tests`() {
        val stale = MapReport("gradle", taskKey, promotedAt, MapFreshness.COLLECTOR_MISMATCH)

        listOf(
            result(reason = "no-map") to "no-map",
            result(reason = "map-unreadable") to "map-unreadable",
            result(reason = "file-not-in-map") to "file-not-in-map",
            result(reason = "stale-map", maps = listOf(stale)) to "stale-map",
        ).forEach { (result, reason) ->
            val view = AffectedMcpCoveringViews.tests("src/Repo.kt", result)
            assertTrue(view.error, reason)
            assertEquals(reason, view.data["reason"])
            assertEquals(emptyList<Any>(), view.data["modules"])
        }
        val noMap = AffectedMcpCoveringViews.tests("src/Repo.kt", result(reason = "no-map"))
        val staleView =
            AffectedMcpCoveringViews.tests("src/Repo.kt", result(reason = "stale-map", maps = listOf(stale)))
        assertTrue(noMap.text.contains("Run the affected tests once"))
        assertTrue(staleView.text.contains("collector-mismatch"))
    }

    @Test
    fun `a fresh map without a covering test is an answer and an unreadable file is an error`() {
        val none = AffectedMcpCoveringViews.tests("src/Repo.kt", result(reason = "no-covering-test"))
        val unreadable = AffectedMcpCoveringViews.tests("src/Repo.kt", null)

        assertFalse(none.error)
        assertEquals("no-covering-test", none.data["reason"])
        assertTrue(unreadable.error)
        assertEquals("unreadable-file", unreadable.data["reason"])
    }

    private fun result(
        recorded: List<CoveringTest> = emptyList(),
        unknown: List<CoveringTest> = emptyList(),
        maps: List<MapReport> = listOf(MapReport("gradle", taskKey, promotedAt, MapFreshness.FRESH)),
        reason: String? = null,
    ) = CoveringTestsResult(setOf("a.Repo"), recorded, unknown, maps, 0, reason)
}
