package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AffectedMcpViewsTest {

    @Test
    fun `read views stay on the snapshot and do not invent a ready plan while analyzing`() {
        val snapshot = snapshot(
            analysisStatus = AnalysisStatus.ANALYZING,
            modules = listOf(module(":stale")),
            changes = changes("/repo/stale.kt"),
            plans = plans(tested = 1),
        )

        val modules = AffectedMcpViews.modules(snapshot)
        val plan = AffectedMcpViews.plan(snapshot, checkConsumers = false)
        val files = AffectedMcpViews.changedFiles(snapshot, "/repo")

        assertTrue(modules.error)
        assertTrue(plan.error)
        assertTrue(files.error)
        assertEquals("analyzing", modules.data["analysisStatus"])
        assertEquals("analyzing", plan.data["analysisStatus"])
        assertFalse(plan.data.containsKey("groups"))
    }

    @Test
    fun `a ready snapshot exposes modules, files and the selected prepared plan`() {
        val testsOnly = prepared(tested = 1, compiled = 0, tasks = listOf(":alpha:test"))
        val withConsumers = prepared(tested = 1, compiled = 1, tasks = listOf(":alpha:test", ":beta:compileTestKotlin"))
        val snapshot = snapshot(
            analysisStatus = AnalysisStatus.READY,
            modules = listOf(module(":alpha", tasks = setOf("detekt"))),
            changes = changes("/repo/alpha/src/Main.kt", api = true),
            plans = Verification.PreparedPlans(testsOnly, withConsumers),
        )

        val modules = AffectedMcpViews.modules(snapshot)
        val files = AffectedMcpViews.changedFiles(snapshot, "/repo")
        val tests = AffectedMcpViews.plan(snapshot, checkConsumers = false)
        val consumers = AffectedMcpViews.plan(snapshot, checkConsumers = true)

        assertFalse(modules.error)
        assertEquals(listOf(":alpha"), modules.data["modules"])
        assertEquals(listOf("alpha/src/Main.kt"), files.data["files"])
        assertEquals(listOf("alpha/src/Main.kt"), files.data["apiTouched"])
        assertEquals(1, tests.data["tested"])
        assertEquals(0, tests.data["compiled"])
        assertEquals(1, consumers.data["compiled"])
        assertEquals(listOf(":alpha:test", ":beta:compileTestKotlin"), consumers.data["tasks"])
    }

    @Test
    fun `named task and branch inputs fail closed with bounded reasons`() {
        val snapshot = snapshot(
            analysisStatus = AnalysisStatus.READY,
            modules = listOf(module(":alpha", tasks = setOf("detekt"))),
        )

        val missing = AffectedMcpInputs.validateNamedTask(snapshot, "lint")
        val blank = AffectedMcpInputs.validateNamedTask(snapshot, "  ")
        val branch = AffectedMcpInputs.validateBaseBranch("../secret")

        assertTrue(missing.error)
        assertTrue(blank.error)
        assertTrue(branch.error)
        assertEquals("unknown-task", missing.data["reason"])
        assertEquals("invalid-task", blank.data["reason"])
        assertEquals("invalid-branch", branch.data["reason"])
    }

    @Test
    fun `status reports owned sessions instead of every IDE run`() {
        val snapshot = snapshot(
            analysisStatus = AnalysisStatus.READY,
            verificationStatus = VerificationStatus.RUNNING,
            modules = listOf(module(":alpha")),
        )

        val status = AffectedMcpViews.status(
            snapshot = snapshot,
            settings = AffectedMcpSettings("main", checkConsumers = true),
            ownedRunning = 1,
        )

        assertFalse(status.error)
        assertEquals(1, status.data["ownedRunning"])
        assertEquals("running", status.data["verificationStatus"])
        assertFalse(status.data.containsKey("runningSessions"))
    }

    private fun snapshot(
        analysisStatus: AnalysisStatus,
        verificationStatus: VerificationStatus = VerificationStatus.IDLE,
        modules: List<AffectedModule> = emptyList(),
        changes: ProjectChanges.Result? = null,
        plans: Verification.PreparedPlans? = null,
    ) = AffectedStateSnapshot(
        revision = 1,
        analysisStatus = analysisStatus,
        modules = modules,
        verificationStatus = verificationStatus,
        changes = changes,
        plans = plans,
    )

    private fun module(id: String, tasks: Set<String> = emptySet()) = AffectedModule(
        id = id,
        systemId = "GRADLE",
        buildRoot = "/repo",
        directory = "/repo$id",
        testDirectory = null,
        testTask = "test",
        compileTask = null,
        hasTests = true,
        tasks = tasks,
    )

    private fun changes(path: String, api: Boolean = false): ProjectChanges.Result {
        val file = File(path)
        return ProjectChanges.Result(
            files = listOf(file),
            apiTouched = if (api) setOf(file) else emptySet(),
            exactSelectionEligible = emptySet(),
            comparedToBase = true,
        )
    }

    private fun plans(tested: Int) = Verification.PreparedPlans(
        testsOnly = prepared(tested, 0, emptyList()),
        withConsumers = prepared(tested, 0, emptyList()),
    )

    private fun prepared(tested: Int, compiled: Int, tasks: List<String>) = Verification.Prepared(
        plan = Plan(listOf(TaskGroup("GRADLE", "/repo", tasks)).filter { it.tasks.isNotEmpty() }, tested, compiled),
        changes = BuildChanges(emptyList(), emptySet(), comparedToBase = true),
    )
}
