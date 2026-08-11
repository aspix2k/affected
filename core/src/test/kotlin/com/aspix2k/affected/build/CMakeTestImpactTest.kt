package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CMakeTestImpactTest {

    @Test
    fun `selects exact tests through target dependencies without matching prefixes`() {
        val root = createTempDirectory("cmake-impact")
        val changed = root.resolve("src/alpha.c")
        Files.createDirectories(changed.parent)
        changed.writeText("int alpha(void) { return 1; }")
        val snapshot = snapshot()

        assertEquals(
            CMakeTestSelection.Exact(listOf("affected_alpha")),
            selectCMakeTests(root, snapshot, snapshot, changes(changed.toString())),
        )
    }

    @Test
    fun `returns proven empty only when complete metadata has no dependent test`() {
        val root = createTempDirectory("cmake-impact")
        val changed = root.resolve("src/unused.c")
        Files.createDirectories(changed.parent)
        changed.writeText("int unused(void) { return 1; }")
        val snapshot = snapshot()

        assertEquals(
            CMakeTestSelection.Empty,
            selectCMakeTests(root, snapshot, snapshot, changes(changed.toString())),
        )
    }

    @Test
    fun `falls back for stale metadata configuration resources and incomplete changes`() {
        val root = createTempDirectory("cmake-impact")
        val source = root.resolve("src/alpha.c")
        val resource = root.resolve("resources/alpha.json")
        val configuration = root.resolve("CMakeLists.txt")
        listOf(source, resource, configuration).forEach {
            Files.createDirectories(it.parent)
            it.writeText("changed")
        }
        val snapshot = snapshot()
        val stale = snapshot.copy(fingerprint = sha256("stale"))

        assertEquals(CMakeTestSelection.Full, selectCMakeTests(root, stale, snapshot, changes(source.toString())))
        assertEquals(CMakeTestSelection.Full, selectCMakeTests(root, snapshot, snapshot, changes(resource.toString())))
        assertEquals(
            CMakeTestSelection.Full,
            selectCMakeTests(root, snapshot, snapshot, changes(configuration.toString())),
        )
        assertEquals(
            CMakeTestSelection.Full,
            selectCMakeTests(
                root,
                snapshot,
                snapshot,
                BuildChanges(listOf(source.toString()), emptySet(), comparedToBase = true),
            ),
        )
        assertEquals(
            CMakeTestSelection.Full,
            selectCMakeTests(root, snapshot, snapshot, changes(source.toString(), comparedToBase = false)),
        )
    }

    @Test
    fun `falls back when any changed source is unmapped or symlinked`() {
        val root = createTempDirectory("cmake-impact")
        val known = root.resolve("src/alpha.c")
        val unknown = root.resolve("src/unknown.c")
        val outside = createTempDirectory("cmake-outside").resolve("alpha.c")
        val link = root.resolve("src/link.c")
        listOf(known, unknown, outside).forEach {
            Files.createDirectories(it.parent)
            it.writeText("changed")
        }
        val snapshot = snapshot()

        assertEquals(
            CMakeTestSelection.Full,
            selectCMakeTests(
                root,
                snapshot,
                snapshot,
                BuildChanges(
                    listOf(known.toString(), unknown.toString()),
                    setOf(known.toString(), unknown.toString()),
                    comparedToBase = true,
                ),
            ),
        )
        assumeTrue(runCatching { Files.createSymbolicLink(link, outside) }.isSuccess)
        val linked = snapshot.copy(
            targets = snapshot.targets + (
                "linked" to CMakeImpactTarget("linked", "linked", setOf("src/link.c"), emptySet())
                ),
        )
        assertEquals(CMakeTestSelection.Full, selectCMakeTests(root, linked, linked, changes(link.toString())))
    }

    @Test
    fun `falls back for headers even when only one target lists them`() {
        val root = createTempDirectory("cmake-impact")
        val header = root.resolve("include/shared.h")
        Files.createDirectories(header.parent)
        header.writeText("int shared(void);")
        val original = snapshot()
        val alpha = original.targets.getValue("alpha-lib")
        val partial = original.copy(
            targets = original.targets + (alpha.id to alpha.copy(sources = alpha.sources + "include/shared.h")),
        )

        assertEquals(CMakeTestSelection.Full, selectCMakeTests(root, partial, partial, changes(header.toString())))
    }

    @Test
    fun `store rejects truncated and modified baselines`() {
        val directory = createTempDirectory("cmake-store")
        val store = CMakeTestBaselineStore(directory)
        store.write(snapshot())
        val baseline = directory.resolve("baseline.map")
        val complete = baseline.toFile().readLines()

        baseline.writeText(complete.dropLast(1).joinToString("\n", postfix = "\n"))
        assertNull(store.read())

        store.write(snapshot())
        baseline.writeText(baseline.toFile().readText().replaceFirst("test=", "test=x"))
        assertNull(store.read())
    }

    @Test
    fun `store round trips a complete snapshot atomically`() {
        val directory = createTempDirectory("cmake-store")
        val store = CMakeTestBaselineStore(directory)
        val snapshot = snapshot()

        store.write(snapshot)

        assertEquals(snapshot, store.read())
        assertEquals(listOf("baseline.map"), directory.toFile().list()?.toList())
        assertFailsWith<IllegalArgumentException> {
            store.write(snapshot.copy(allTests = snapshot.allTests + "unmapped"))
        }
    }

    @Test
    fun `only a complete successful full report promotes the baseline`() {
        val directory = createTempDirectory("cmake-promotion")
        val store = CMakeTestBaselineStore(directory)
        val previous = snapshot()
        val current = previous.copy(fingerprint = sha256("current"))
        val complete = directory.resolve("complete.xml")
        val skipped = directory.resolve("skipped.xml")
        complete.writeText(report(current.allTests))
        skipped.writeText(report(current.allTests, skipped = 1))
        store.write(previous)

        assertFalse(promoteCMakeBaseline(store, current, current, complete, full = false, passed = true))
        assertEquals(previous, store.read())
        assertFalse(promoteCMakeBaseline(store, current, current, complete, full = true, passed = false))
        assertEquals(previous, store.read())
        assertFalse(promoteCMakeBaseline(store, previous, current, complete, full = true, passed = true))
        assertEquals(previous, store.read())
        assertFalse(promoteCMakeBaseline(store, current, current, skipped, full = true, passed = true))
        assertEquals(previous, store.read())
        assertTrue(promoteCMakeBaseline(store, current, current, complete, full = true, passed = true))
        assertEquals(current, store.read())
    }

    @Test
    fun `report rejects missing duplicate and external entity test evidence`() {
        val directory = createTempDirectory("cmake-report")
        val missing = directory.resolve("missing.xml")
        val duplicate = directory.resolve("duplicate.xml")
        val entity = directory.resolve("entity.xml")
        missing.writeText(report(setOf("affected_alpha")))
        duplicate.writeText(
            "<testsuite tests=\"2\" failures=\"0\"><testcase name=\"affected_alpha\"/>" +
                "<testcase name=\"affected_alpha\"/></testsuite>",
        )
        entity.writeText(
            "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" +
                "<testsuite tests=\"0\" failures=\"0\"/>",
        )

        assertFalse(completeCTestReport(missing, snapshot().allTests))
        assertFalse(completeCTestReport(duplicate, snapshot().allTests))
        assertFalse(completeCTestReport(entity, snapshot().allTests))
    }

    private fun snapshot(): CMakeTestSnapshot {
        val targets = listOf(
            CMakeImpactTarget("alpha-lib", "alpha_lib", setOf("src/alpha.c"), emptySet()),
            CMakeImpactTarget("alpha-test", "affected_alpha", setOf("tests/alpha_test.c"), setOf("alpha-lib")),
            CMakeImpactTarget(
                "alpha-prefix-test",
                "affected_alpha_extended",
                setOf("tests/alpha_extended_test.c"),
                emptySet(),
            ),
            CMakeImpactTarget("unused-lib", "unused_lib", setOf("src/unused.c"), emptySet()),
        ).associateBy(CMakeImpactTarget::id)
        return CMakeTestSnapshot(
            sha256("stable"),
            targets,
            mapOf(
                "affected_alpha" to "alpha-test",
                "affected_alpha_extended" to "alpha-prefix-test",
            ),
            setOf("affected_alpha", "affected_alpha_extended"),
        )
    }

    private fun changes(path: String, comparedToBase: Boolean = true): BuildChanges =
        BuildChanges(listOf(path), setOf(path), comparedToBase)

    private fun report(tests: Set<String>, skipped: Int = 0): String = buildString {
        append("<testsuite tests=\"").append(tests.size).append("\" failures=\"0\" errors=\"0\" skipped=\"")
            .append(skipped).append("\" disabled=\"0\">")
        tests.forEachIndexed { index, test ->
            append("<testcase name=\"").append(test).append("\">")
            if (index < skipped) append("<skipped/>")
            append("</testcase>")
        }
        append("</testsuite>")
    }
}
