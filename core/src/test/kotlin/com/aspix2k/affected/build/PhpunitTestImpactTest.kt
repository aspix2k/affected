package com.aspix2k.affected.build

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhpunitTestImpactTest {

    @Test
    fun `selects exact PHPUnit test files from a complete baseline`() {
        val root = project()
        val baseline = snapshot()
        val current = state(baseline)

        assertEquals(
            PhpunitTestSelection.Exact(listOf("Affected\\AlphaTest"), listOf("Affected\\AlphaTest::testAlpha")),
            selectPhpunitTests(root, current, baseline, changes(root.resolve("packages/alpha/src/Alpha.php"))),
        )
    }

    @Test
    fun `falls back when a changed production file is unobserved`() {
        val root = project()
        val baseline = snapshot()

        assertEquals(
            PhpunitTestSelection.Full,
            selectPhpunitTests(root, state(baseline), baseline, changes(root.resolve("packages/alpha/src/Unused.php"))),
        )
    }

    @Test
    fun `falls back for resource test and artifact drift`() {
        val root = project()
        val baseline = snapshot()
        val resource = root.resolve("packages/alpha/schema.json").apply { writeText("{}") }
        val test = root.resolve("packages/alpha/tests/AlphaTest.php")
        val drifted = state(baseline).copy(
            artifacts = state(baseline).artifacts + ("packages/alpha/src/Beta.php" to sha256("drift")),
        )

        assertEquals(PhpunitTestSelection.Full, selectPhpunitTests(root, state(baseline), baseline, changes(resource)))
        assertEquals(PhpunitTestSelection.Full, selectPhpunitTests(root, state(baseline), baseline, changes(test)))
        assertEquals(
            PhpunitTestSelection.Full,
            selectPhpunitTests(root, drifted, baseline, changes(root.resolve("packages/alpha/src/Alpha.php"))),
        )
    }

    @Test
    fun `falls back for incomplete changes stale identity and artifact set drift`() {
        val root = project()
        val baseline = snapshot()
        val alpha = root.resolve("packages/alpha/src/Alpha.php")
        val current = state(baseline)

        assertEquals(
            PhpunitTestSelection.Full,
            selectPhpunitTests(root, current, baseline, BuildChanges(listOf(alpha.toString()), emptySet(), true)),
        )
        assertEquals(
            PhpunitTestSelection.Full,
            selectPhpunitTests(root, current.copy(identity = sha256("stale")), baseline, changes(alpha)),
        )
        assertEquals(
            PhpunitTestSelection.Full,
            selectPhpunitTests(
                root,
                current.copy(artifacts = current.artifacts - "packages/alpha/src/Unused.php"),
                baseline,
                changes(alpha),
            ),
        )
    }

    @Test
    fun `store rejects truncated and modified PHPUnit baselines`() {
        val directory = createTempDirectory("phpunit-store")
        val store = PhpunitTestBaselineStore(directory)
        store.write(snapshot())
        val baseline = directory.resolve("baseline.map")
        val lines = baseline.toFile().readLines()

        baseline.writeText(lines.dropLast(1).joinToString("\n", postfix = "\n"))
        assertNull(store.read())

        store.write(snapshot())
        baseline.writeText(baseline.toFile().readText().replaceFirst("dependency=", "dependency=x"))
        assertNull(store.read())
    }

    @Test
    fun `store round trips a complete PHPUnit baseline`() {
        val directory = createTempDirectory("phpunit-store")
        val store = PhpunitTestBaselineStore(directory)
        val snapshot = snapshot()

        store.write(snapshot)

        assertEquals(snapshot, store.read())
        assertEquals(listOf("baseline.map"), directory.toFile().list()?.toList())
    }

    @Test
    fun `only a complete unchanged full run promotes the PHPUnit baseline`() {
        val directory = createTempDirectory("phpunit-promotion")
        val store = PhpunitTestBaselineStore(directory)
        val previous = snapshot()
        val before = state(previous).copy(identity = sha256("current"))
        val output = directory.resolve("run.json")
        output.writeText(runMap(full = true))
        store.write(previous)

        assertFalse(promotePhpunitBaseline(store, before, before, output, full = false, passed = true))
        assertEquals(previous, store.read())
        assertFalse(promotePhpunitBaseline(store, before, before, output, full = true, passed = false))
        assertEquals(previous, store.read())
        assertFalse(
            promotePhpunitBaseline(
                store,
                before,
                before.copy(identity = sha256("changed")),
                output,
                full = true,
                passed = true,
            ),
        )
        assertEquals(previous, store.read())
        assertTrue(promotePhpunitBaseline(store, before, before, output, full = true, passed = true))
        assertEquals(before.identity, store.read()?.identity)
    }

    @Test
    fun `selected PHPUnit run requires exact complete file evidence and cannot promote`() {
        val directory = createTempDirectory("phpunit-selected")
        val store = PhpunitTestBaselineStore(directory)
        val baseline = snapshot()
        val current = state(baseline)
        val output = directory.resolve("selected.json")
        output.writeText(runMap(full = false, beta = false))
        store.write(baseline)
        val selection = PhpunitTestSelection.Exact(
            listOf("Affected\\AlphaTest"),
            listOf("Affected\\AlphaTest::testAlpha"),
        )

        assertTrue(completePhpunitSelection(selection, current, current, output, baseline))
        assertFalse(promotePhpunitBaseline(store, current, current, output, full = false, passed = true))
        assertEquals(baseline, store.read())

        val splitFiles = baseline.copy(
            classes = baseline.classes + ("Affected\\BetaTest" to "packages/alpha/tests/BetaTest.php"),
        )
        assertFalse(completePhpunitSelection(selection, current, current, output, splitFiles))

        output.writeText(runMap(full = false))
        assertFalse(completePhpunitSelection(selection, current, current, output, baseline))
    }

    @Test
    fun `class filter is anchored escaped and deterministic`() {
        assertEquals(
            "~^(?:Alpha\\\\Epsilon|Beta\\\\Test)::~D",
            phpunitClassFilter(listOf("Beta\\Test", "Alpha\\Epsilon")),
        )
    }

    @Test
    fun `oversized exact selection falls back to the full package`() {
        val baseline = snapshot()
        val classes = (0..256).map { "Affected\\Test$it" }
        val oversized = PhpunitTestSelection.Exact(classes, classes.map { "$it::testValue" })

        assertEquals(PhpunitTestSelection.Full, boundedPhpunitSelection(oversized, baseline))
    }

    private fun project() = createTempDirectory("phpunit-impact").also { root ->
        listOf(
            "packages/alpha/src/Alpha.php",
            "packages/alpha/src/Beta.php",
            "packages/alpha/src/Unused.php",
            "packages/alpha/tests/AlphaTest.php",
            "packages/alpha/tests/BetaTest.php",
        ).forEach { relative ->
            root.resolve(relative).also { it.parent.createDirectories() }.writeText("<?php\n")
        }
    }

    private fun state(snapshot: PhpunitTestSnapshot) = PhpunitProjectState(snapshot.identity, snapshot.artifacts)

    private fun snapshot() = PhpunitTestSnapshot(
        identity = sha256("identity"),
        artifacts = mapOf(
            "packages/alpha/src/Alpha.php" to sha256("alpha"),
            "packages/alpha/src/Beta.php" to sha256("beta"),
            "packages/alpha/src/Unused.php" to sha256("unused"),
        ),
        tests = mapOf(
            "Affected\\AlphaTest::testAlpha" to "Affected\\AlphaTest",
            "Affected\\BetaTest::testBeta" to "Affected\\BetaTest",
        ),
        classes = mapOf(
            "Affected\\AlphaTest" to "packages/alpha/tests/AlphaTest.php",
            "Affected\\BetaTest" to "packages/alpha/tests/AlphaTest.php",
        ),
        dependencies = mapOf(
            "Affected\\AlphaTest" to setOf("packages/alpha/src/Alpha.php"),
            "Affected\\BetaTest" to setOf("packages/alpha/src/Beta.php"),
        ),
    )

    private fun changes(path: java.nio.file.Path) =
        BuildChanges(listOf(path.toString()), setOf(path.toString()), comparedToBase = true)

    private fun runMap(full: Boolean, beta: Boolean = true): String = buildString {
        append("{\"schema\":2,\"full\":").append(full)
        append(",\"supported\":true,\"complete\":true")
        append(",\"test_count\":").append(if (beta) 2 else 1)
        append(",\"class_count\":").append(if (beta) 2 else 1)
        append(",\"dependency_owner_count\":").append(if (beta) 2 else 1)
        append(",\"dependency_count\":").append(if (beta) 2 else 1)
        append(",\"inventory_test_count\":2,\"inventory_class_count\":2")
        append(",\"tests\":[")
        append("{\"id\":\"Affected\\\\AlphaTest::testAlpha\",\"class\":\"Affected\\\\AlphaTest\",")
        append("\"file\":\"packages/alpha/tests/AlphaTest.php\"}")
        if (beta) {
            append(",{\"id\":\"Affected\\\\BetaTest::testBeta\",\"class\":\"Affected\\\\BetaTest\",")
            append("\"file\":\"packages/alpha/tests/AlphaTest.php\"}")
        }
        append("],\"dependencies\":{")
        append("\"Affected\\\\AlphaTest\":[\"packages/alpha/src/Alpha.php\"]")
        if (beta) {
            append(",\"Affected\\\\BetaTest\":[\"packages/alpha/src/Beta.php\"]")
        }
        append("},\"inventory\":[")
        append("{\"id\":\"Affected\\\\AlphaTest::testAlpha\",\"class\":\"Affected\\\\AlphaTest\",")
        append("\"file\":\"packages/alpha/tests/AlphaTest.php\"},")
        append("{\"id\":\"Affected\\\\BetaTest::testBeta\",\"class\":\"Affected\\\\BetaTest\",")
        append("\"file\":\"packages/alpha/tests/AlphaTest.php\"}]}")
    }
}
