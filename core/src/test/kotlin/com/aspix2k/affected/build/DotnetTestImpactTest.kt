package com.aspix2k.affected.build

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DotnetTestImpactTest {

    @Test
    fun `a changed assembly selects only test identities mapped to it`() {
        val fixture = fixture()
        val baseline = snapshot(alphaHash = hash('a'), betaHash = hash('b'))
        val current = snapshot(alphaHash = hash('c'), betaHash = hash('b'))

        val selection = selectDotnetTests(
            fixture.root,
            fixture.productionRoots,
            current,
            baseline,
            fixture.changes,
        )

        assertEquals(
            listOf("Example.Tests.AlphaTest.Passes"),
            assertIs<DotnetTestSelection.Exact>(selection).tests,
        )
    }

    @Test
    fun `a transitive artifact change selects tests of its consumers`() {
        val fixture = fixture()
        val baseline = snapshot(alphaHash = hash('a'), betaHash = hash('b'), alphaDependencies = setOf("Beta"))
        val current = snapshot(alphaHash = hash('a'), betaHash = hash('c'), alphaDependencies = setOf("Beta"))

        val selection = selectDotnetTests(
            fixture.root,
            fixture.productionRoots,
            current,
            baseline,
            fixture.changes.copy(
                files = listOf(fixture.betaSource.toString()),
                exactSelectionEligible = setOf(fixture.betaSource.toString()),
            ),
        )

        assertEquals(
            listOf("Example.Tests.AlphaTest.Passes", "Example.Tests.BetaTest.Passes"),
            assertIs<DotnetTestSelection.Exact>(selection).tests,
        )
    }

    @Test
    fun `unchanged compiled artifacts prove an empty test selection`() {
        val fixture = fixture()
        val baseline = snapshot(alphaHash = hash('a'), betaHash = hash('b'))

        assertEquals(
            DotnetTestSelection.Empty,
            selectDotnetTests(fixture.root, fixture.productionRoots, baseline, baseline, fixture.changes),
        )
    }

    @Test
    fun `an unchanged conservative consumer proves an empty selection`() {
        val fixture = fixture()
        val baseline = snapshot(alphaHash = hash('a'), betaHash = hash('b'))

        assertEquals(
            DotnetTestSelection.Empty,
            selectUnchangedDotnetConsumer(fixture.root, baseline, baseline, fixture.changes),
        )
    }

    @Test
    fun `a changed conservative consumer stays full`() {
        val fixture = fixture()
        val baseline = snapshot(alphaHash = hash('a'), betaHash = hash('b'))
        val current = snapshot(alphaHash = hash('c'), betaHash = hash('b'))

        assertEquals(
            DotnetTestSelection.Full,
            selectUnchangedDotnetConsumer(fixture.root, current, baseline, fixture.changes),
        )
        assertEquals(
            DotnetTestSelection.Full,
            selectUnchangedDotnetConsumer(
                fixture.root,
                baseline,
                baseline,
                fixture.changes.copy(comparedToBase = false),
            ),
        )
    }

    @Test
    fun `a changed artifact without a mapped test keeps the full project`() {
        val fixture = fixture()
        val baseline = snapshot(alphaHash = hash('a'), betaHash = hash('b')).copy(
            classes = mapOf("Example.Tests.AlphaTest" to setOf("Alpha")),
            tests = mapOf("Example.Tests.AlphaTest.Passes" to "Example.Tests.AlphaTest"),
        )
        val current = baseline.copy(
            artifacts = baseline.artifacts + ("Beta" to DotnetImpactArtifact(hash('c'), emptySet())),
        )

        assertEquals(
            DotnetTestSelection.Full,
            selectDotnetTests(
                fixture.root,
                fixture.productionRoots,
                current,
                baseline,
                fixture.changes.copy(
                    files = listOf(fixture.betaSource.toString()),
                    exactSelectionEligible = setOf(fixture.betaSource.toString()),
                ),
            ),
        )
    }

    @Test
    fun `test source and incomplete change evidence keep the full project`() {
        val fixture = fixture()
        val baseline = snapshot(alphaHash = hash('a'), betaHash = hash('b'))
        val changed = snapshot(alphaHash = hash('c'), betaHash = hash('b'))
        val testSource = fixture.root.resolve("tests/Example.Tests/AlphaTest.cs").apply {
            parent.createDirectories()
            createFile()
        }

        assertEquals(
            DotnetTestSelection.Full,
            selectDotnetTests(
                fixture.root,
                fixture.productionRoots,
                changed,
                baseline,
                fixture.changes.copy(
                    files = listOf(testSource.toString()),
                    exactSelectionEligible = setOf(testSource.toString()),
                ),
            ),
        )
        assertEquals(
            DotnetTestSelection.Full,
            selectDotnetTests(
                fixture.root,
                fixture.productionRoots,
                changed,
                baseline,
                fixture.changes.copy(comparedToBase = false),
            ),
        )
        val generated = fixture.alphaSource.resolveSibling("Alpha.g.cs").apply { createFile() }
        assertEquals(
            DotnetTestSelection.Full,
            selectDotnetTests(
                fixture.root,
                fixture.productionRoots,
                changed,
                baseline,
                fixture.changes.copy(
                    files = listOf(generated.toString()),
                    exactSelectionEligible = setOf(generated.toString()),
                ),
            ),
        )
    }

    @Test
    fun `baseline store rejects a valid truncated record`() {
        val directory = createTempDirectory("dotnet-baseline")
        val store = DotnetTestBaselineStore(directory)
        val snapshot = snapshot(alphaHash = hash('a'), betaHash = hash('b'))
        store.write(snapshot)
        val baseline = directory.resolve("baseline.map")
        val lines = Files.readAllLines(baseline).toMutableList()
        lines.removeAt(lines.indexOfLast { it.startsWith("test=") })
        baseline.writeText(lines.joinToString("\n", postfix = "\n"))

        assertNull(store.read())
    }

    @Test
    fun `baseline store round trips a complete map`() {
        val store = DotnetTestBaselineStore(createTempDirectory("dotnet-baseline"))
        val snapshot = snapshot(alphaHash = hash('a'), betaHash = hash('b'))

        store.write(snapshot)

        assertEquals(snapshot, store.read())
    }

    private fun fixture(): Fixture {
        val root = createTempDirectory("dotnet-impact")
        val alpha = root.resolve("src/Alpha").apply { createDirectories() }
        val beta = root.resolve("src/Beta").apply { createDirectories() }
        val alphaSource = alpha.resolve("Alpha.cs").apply { createFile() }
        val betaSource = beta.resolve("Beta.cs").apply { createFile() }
        return Fixture(
            root,
            setOf(alpha, beta),
            alphaSource,
            betaSource,
            BuildChanges(listOf(alphaSource.toString()), setOf(alphaSource.toString()), comparedToBase = true),
        )
    }

    private fun snapshot(
        alphaHash: String,
        betaHash: String,
        alphaDependencies: Set<String> = emptySet(),
    ): DotnetTestSnapshot = DotnetTestSnapshot(
        identity = hash('1'),
        testAssemblySha256 = hash('2'),
        artifacts = mapOf(
            "Alpha" to DotnetImpactArtifact(alphaHash, alphaDependencies),
            "Beta" to DotnetImpactArtifact(betaHash, emptySet()),
        ),
        classes = mapOf(
            "Example.Tests.AlphaTest" to setOf("Alpha"),
            "Example.Tests.BetaTest" to setOf("Beta"),
        ),
        tests = mapOf(
            "Example.Tests.AlphaTest.Passes" to "Example.Tests.AlphaTest",
            "Example.Tests.BetaTest.Passes" to "Example.Tests.BetaTest",
        ),
    )

    private fun hash(character: Char): String = character.toString().repeat(64)

    private data class Fixture(
        val root: Path,
        val productionRoots: Set<Path>,
        val alphaSource: Path,
        val betaSource: Path,
        val changes: BuildChanges,
    )
}
