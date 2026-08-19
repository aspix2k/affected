package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AffectedPresentationRefreshTest {

    @Test
    fun `invalidate complete and fail each request one toolbar refresh`() {
        val refreshes = AtomicInteger()
        val store = AffectedStateStore { refreshes.incrementAndGet() }

        val first = store.invalidate()
        assertEquals(1, refreshes.get())
        assertEquals(AnalysisStatus.ANALYZING, store.snapshot().analysisStatus)

        assertTrue(store.complete(first, listOf(module())))
        assertEquals(2, refreshes.get())
        assertEquals(AnalysisStatus.READY, store.snapshot().analysisStatus)

        val second = store.invalidate()
        assertTrue(store.fail(second))
        assertEquals(4, refreshes.get())
        assertEquals(AnalysisStatus.UNAVAILABLE, store.snapshot().analysisStatus)
    }

    @Test
    fun `a stale complete or fail does not refresh the toolbar`() {
        val refreshes = AtomicInteger()
        val store = AffectedStateStore { refreshes.incrementAndGet() }
        val published = store.invalidate()
        store.invalidate()

        assertFalse(store.complete(published, listOf(module())))
        assertFalse(store.fail(published))
        assertEquals(2, refreshes.get())
    }

    @Test
    fun `claim run and finish refresh because the toolbar presentation changes`() {
        val refreshes = AtomicInteger()
        val store = AffectedStateStore { refreshes.incrementAndGet() }
        val revision = store.invalidate()
        store.complete(revision, listOf(module()))
        refreshes.set(0)

        val claim = requireNotNull(store.tryClaimReadyRun())
        assertEquals(1, refreshes.get())
        assertEquals(VerificationStatus.PREPARING, store.snapshot().verificationStatus)

        assertTrue(claim.markRunning())
        assertEquals(2, refreshes.get())
        assertEquals(VerificationStatus.RUNNING, store.snapshot().verificationStatus)

        claim.close()
        assertEquals(3, refreshes.get())
        assertEquals(VerificationStatus.IDLE, store.snapshot().verificationStatus)
    }

    @Test
    fun `a git commit still compared to the configured base republishes a fresh plan`() {
        val directory = createTempDirectory("affected-toolbar-commit").toFile()
        try {
            git(directory, "init", "-q", "-b", "main")
            git(directory, "config", "user.email", "test@example.com")
            git(directory, "config", "user.name", "test")
            File(directory, "settings.gradle.kts").writeText("rootProject.name = \"probe\"")
            File(directory, "lib/src/main/kotlin").mkdirs()
            File(directory, "lib/build.gradle.kts").writeText("")
            val source = File(directory, "lib/src/main/kotlin/Sample.kt")
            source.writeText("package probe\n\nclass Sample\n")
            git(directory, "add", "-A")
            git(directory, "commit", "-qm", "init")
            git(directory, "checkout", "-qb", "feature")
            source.appendText("\nfun afterCommit(): Int = 7\n")
            git(directory, "add", "-A")
            git(directory, "commit", "-qm", "work")

            val analyzer = ChangeAnalyzer(directory, "main")
            val collected = analyzer.collect()
            val changes = ProjectChanges.Result(
                files = collected.files,
                apiTouched = collected.apiTouched,
                exactSelectionEligible = analyzer.modifiedAgainstBase(),
                comparedToBase = analyzer.hasComparisonBase(),
            )
            assertTrue(changes.comparedToBase, "branch commits stay compared to the configured base")
            assertTrue(changes.files.isNotEmpty(), "committed branch work remains affected")

            val refreshes = AtomicInteger()
            val store = AffectedStateStore { refreshes.incrementAndGet() }
            val before = store.invalidate()
            store.complete(
                before,
                AffectedAnalysis(
                    modules = listOf(module(":before")),
                    changes = ProjectChanges.Result(emptyList(), emptySet(), emptySet(), comparedToBase = true),
                    plans = emptyPlans(0),
                ),
            )
            val after = store.invalidate()
            val published = store.complete(
                after,
                AffectedAnalysis(
                    modules = listOf(module(":after")),
                    changes = changes,
                    plans = emptyPlans(changes.files.size),
                ),
            )

            assertTrue(published)
            assertEquals(":after", store.snapshot().modules.single().id)
            assertEquals(changes.files, store.snapshot().changes?.files)
            assertEquals(true, store.snapshot().changes?.comparedToBase)
            assertEquals(changes.files.size, store.snapshot().plans?.testsOnly?.plan?.tested)
            assertEquals(4, refreshes.get())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun git(directory: File, vararg args: String) {
        val completed = ProcessBuilder(listOf("git") + args)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
            .waitFor()
        assertEquals(0, completed, "git ${args.joinToString(" ")}")
    }

    private fun module(id: String = ":ready") = AffectedModule(
        id = id,
        systemId = "GRADLE",
        buildRoot = "/repo",
        directory = "/repo/ready",
        testDirectory = null,
        testTask = "test",
        compileTask = null,
        hasTests = true,
        tasks = emptySet(),
    )

    private fun emptyPlans(affected: Int): Verification.PreparedPlans {
        val prepared = Verification.Prepared(
            Plan(emptyList(), affected, 0),
            BuildChanges(emptyList(), emptySet(), comparedToBase = true),
        )
        return Verification.PreparedPlans(prepared, prepared)
    }
}
