package com.aspix2k.affected.impact

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoveringTestsTest : CollectorMapFixture() {

    private val promotedAt = Instant.parse("2026-01-02T00:00:00Z")
    private val older = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `a test recorded on the class covers its file and unknown tests are listed apart`() {
        val result =
            find(listOf(gradle(stored(":app:test", "Repo" to listOf("RepoTest"), unknown = listOf("AllTest")))))

        assertEquals(null, result.reason)
        assertEquals(setOf("com.acme.Repo"), result.classes)
        assertEquals(listOf("RepoTest"), result.recorded.map(CoveringTest::testClass))
        assertEquals(listOf("AllTest"), result.unknown.map(CoveringTest::testClass))
        assertEquals(MapFreshness.FRESH, result.maps.single().freshness)
    }

    @Test
    fun `Kotlin facades and nested classes are matched and unrelated classes are not`() {
        val artifacts =
            listOf("com.acme.RepoKt", "com.acme.Repo\$Inner", "com.acme.Repo\$Inner\$1", "com.acme.RepoOther")
        val records =
            listOf("FacadeTest" to listOf("com.acme.RepoKt"), "OtherTest" to listOf("com.acme.RepoOther"))
        val map = promoted(":app:test", artifacts, records)

        val result = find(listOf(gradle(map)), names = setOf("com.acme.Repo", "com.acme.RepoKt"))

        assertEquals(setOf("com.acme.RepoKt", "com.acme.Repo\$Inner", "com.acme.Repo\$Inner\$1"), result.classes)
        assertEquals(listOf("FacadeTest"), result.recorded.map(CoveringTest::testClass))
    }

    @Test
    fun `a map from another collector is reported stale and its tests are withheld`() {
        val result = find(
            listOf(gradle(stored(":app:test", "Repo" to listOf("RepoTest")), collector = "collector-2")),
        )

        assertEquals("stale-map", result.reason)
        assertEquals(emptyList(), result.recorded)
        assertEquals(MapFreshness.COLLECTOR_MISMATCH, result.maps.single().freshness)
    }

    @Test
    fun `an unverifiable collector and a newer source both make the map stale`() {
        val map = stored(":app:test", "Repo" to listOf("RepoTest"))

        assertEquals(
            MapFreshness.COLLECTOR_UNVERIFIED,
            find(listOf(gradle(map, collector = null))).maps.single().freshness,
        )
        assertEquals(
            MapFreshness.SOURCE_NEWER,
            find(listOf(gradle(map)), modified = promotedAt.plusSeconds(1)).maps.single().freshness,
        )
    }

    @Test
    fun `a file without recorded classes and missing maps give distinct reasons`() {
        val map = stored(":app:test", "Repo" to listOf("RepoTest"))

        assertEquals("file-not-in-map", find(listOf(gradle(map)), names = setOf("com.acme.Missing")).reason)
        assertEquals("no-map", find(listOf(PromotedMaps("gradle", StoredMaps(emptyList(), 0), "collector-1"))).reason)
        assertEquals("map-unreadable", find(listOf(none(2))).reason)
    }

    @Test
    fun `a fresh map that records no test on the class says so`() {
        val result = find(listOf(gradle(stored(":app:test", "Repo" to emptyList()))))

        assertEquals("no-covering-test", result.reason)
        assertEquals(setOf("com.acme.Repo"), result.classes)
    }

    @Test
    fun `maps from several modules and systems are grouped by their own task`() {
        val first = stored(":app:test", "Repo" to listOf("AppTest"))
        val second = promoted(":lib:test", listOf("com.acme.Repo"), listOf("LibTest" to listOf("com.acme.Repo")))
        val maven = promoted("test", listOf("com.acme.Repo"), listOf("MvnTest" to listOf("com.acme.Repo")))

        val result =
            find(listOf(gradle(first, second), PromotedMaps("maven", StoredMaps(listOf(maven), 0), "collector-1")))

        assertEquals(
            listOf("AppTest" to ":app:test", "LibTest" to ":lib:test", "MvnTest" to "test"),
            result.recorded.map { it.testClass to CoveringTestsFinder.runTarget(it.taskKey)?.task },
        )
        assertEquals(listOf("gradle", "gradle", "maven"), result.maps.map(MapReport::system))
    }

    @Test
    fun `a stale map next to a fresh one does not hide the fresh answer`() {
        val fresh = stored(":app:test", "Repo" to listOf("AppTest"))
        val stale =
            promoted(
                ":lib:test",
                listOf("com.acme.Repo"),
                listOf("LibTest" to listOf("com.acme.Repo")),
                collector = "collector-0",
            )

        val result = find(listOf(gradle(fresh, stale)))

        assertEquals(null, result.reason)
        assertEquals(listOf("AppTest"), result.recorded.map(CoveringTest::testClass))
        assertEquals(
            setOf(MapFreshness.FRESH, MapFreshness.COLLECTOR_MISMATCH),
            result.maps.map(MapReport::freshness).toSet(),
        )
    }

    @Test
    fun `source class names follow the package and file name conventions`() {
        assertEquals(
            setOf("com.acme.Repo", "com.acme.RepoKt"),
            SourceClassNames.of("Repo.kt", "package com.acme\n\nclass Repo"),
        )
        assertEquals(setOf("com.acme.Repo"), SourceClassNames.of("Repo.java", "package com.acme;\nclass Repo {}"))
        assertEquals(setOf("Repo", "RepoKt"), SourceClassNames.of("Repo.kt", "class Repo"))
        assertNull(SourceClassNames.of("Repo.scala", "class Repo"))
    }

    @Test
    fun `run targets and commands come from the task key`() {
        val target = CoveringTestsFinder.runTarget("file:///work/app/|:app:test")

        assertEquals(RunTarget("/work/app", ":app:test"), target)
        assertNull(CoveringTestsFinder.runTarget("not-a-key"))
        assertEquals(
            "./gradlew :app:test --tests 'a.BTest' --tests 'a.CTest\$Inner'",
            CoveringTestsFinder.command("gradle", ":app:test", listOf("a.BTest", "a.CTest\$Inner", "bad name;")),
        )
        assertEquals("mvn test -Dtest='a.BTest'", CoveringTestsFinder.command("maven", "test", listOf("a.BTest")))
        assertEquals(
            "mvn verify -Dit.test='a.BIT'",
            CoveringTestsFinder.command("maven", "integration-test", listOf("a.BIT")),
        )
        assertNull(CoveringTestsFinder.command("gradle", ":app:test", listOf("bad name")))
    }

    @Test
    fun `reading the store lists promoted maps without creating the directory and counts unreadable files`() =
        withDirectory { root ->
            val missing = root.resolve("maps")
            assertEquals(StoredMaps(emptyList(), 0), DependencyMapStore(missing).readAll())
            assertEquals(false, Files.exists(missing))

            val store = DependencyMapStore(missing)
            val map = complete(
                listOf(dependency("Alpha", "alpha-1")),
                listOf(record(testClass("AlphaTest"), dependency("Alpha", "alpha-1"))),
            )
            store.write(map)
            Files.writeString(missing.resolve("map-${"0".repeat(64)}.map"), "garbage\n")

            val stored = store.readAll()

            assertEquals(listOf(map), stored.maps.map(PromotedMap::map))
            assertEquals(1, stored.unreadable)
        }

    private fun find(
        sources: List<PromotedMaps>,
        names: Set<String> = setOf("com.acme.Repo"),
        modified: Instant = older,
    ) = CoveringTestsFinder.find(sources, names, modified)

    private fun none(unreadable: Int) = PromotedMaps("gradle", StoredMaps(emptyList(), unreadable), "collector-1")

    private fun gradle(vararg maps: PromotedMap, collector: String? = "collector-1") =
        PromotedMaps("gradle", StoredMaps(maps.toList(), 0), collector)

    private fun stored(
        task: String,
        vararg classes: Pair<String, List<String>>,
        unknown: List<String> = emptyList(),
    ): PromotedMap = promoted(
        task,
        classes.map { "com.acme.${it.first}" },
        classes.flatMap { (name, tests) -> tests.map { it to listOf("com.acme.$name") } },
        unknown,
    )

    private fun promoted(
        task: String,
        artifacts: List<String>,
        records: List<Pair<String, List<String>>>,
        unknown: List<String> = emptyList(),
        collector: String = "collector-1",
    ): PromotedMap {
        val catalog = artifacts.map { dependency(it, "hash-$it") }
        val byName = catalog.associateBy { it.id.className }
        val map = CompleteDependencyMap(
            identity = DependencyMapIdentity(
                DEPENDENCY_MAP_SCHEMA_VERSION,
                collector,
                "file:///work/app/|$task",
                "runtime-1",
                "input-1",
            ),
            artifacts = catalog,
            records = records.map { (test, names) ->
                record(testClass(test), *names.map(byName::getValue).toTypedArray())
            } +
                unknown.map { TestDependencyRecord(testClass(it), emptySet(), unknownDependencies = true) },
            completedRunId = "run-1",
        )
        return PromotedMap(map, promotedAt)
    }
}
