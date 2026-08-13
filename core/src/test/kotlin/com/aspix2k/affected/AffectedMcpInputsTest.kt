package com.aspix2k.affected

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AffectedMcpInputsTest {

    private val ready = snapshot(
        AnalysisStatus.READY,
        modules = listOf(module(":alpha", tasks = setOf("detekt", "A" + "x".repeat(127)))),
    )
    private val current = AffectedMcpSettings(
        baseBranch = "main",
        checkConsumers = false,
        runBeforeCommit = false,
        runBeforePush = true,
        animateWhileRunning = true,
    )

    @Test
    fun `an analyzing snapshot cannot validate a known task`() {
        val view = AffectedMcpInputs.validateNamedTask(
            snapshot(AnalysisStatus.ANALYZING, modules = listOf(module(":alpha", tasks = setOf("detekt")))),
            "detekt",
        )
        assertTrue(view.error)
        assertEquals("analyzing", view.data["analysisStatus"])
    }

    @Test
    fun `task names are accepted at the 128 character bound and rejected past it`() {
        val allowed = "A" + "x".repeat(127)
        val tooLong = allowed + "y"
        val accepted = AffectedMcpInputs.validateNamedTask(ready, allowed)
        val rejected = AffectedMcpInputs.validateNamedTask(ready, tooLong)
        assertFalse(accepted.error)
        assertEquals(allowed, accepted.data["task"])
        assertTrue(rejected.error)
        assertEquals("invalid-task", rejected.data["reason"])
    }

    @Test
    fun `a task name must start with a letter`() {
        val view = AffectedMcpInputs.validateNamedTask(ready, "1detekt")
        assertTrue(view.error)
        assertEquals("invalid-task", view.data["reason"])
    }

    @Test
    fun `a branch that matches the charset but contains a parent segment is rejected`() {
        val view = AffectedMcpInputs.validateBaseBranch("release/../x")
        assertTrue(view.error)
        assertEquals("invalid-branch", view.data["reason"])
    }

    @Test
    fun `a branch with a character outside the charset is rejected`() {
        val view = AffectedMcpInputs.validateBaseBranch("main@origin")
        assertTrue(view.error)
        assertEquals("invalid-branch", view.data["reason"])
    }

    @Test
    fun `branch names are accepted at the 255 character bound and rejected past it`() {
        val allowed = "r" + "e".repeat(254)
        val tooLong = allowed + "x"
        val accepted = AffectedMcpInputs.validateBaseBranch(allowed)
        val rejected = AffectedMcpInputs.validateBaseBranch(tooLong)
        assertFalse(accepted.error)
        assertEquals(allowed, accepted.data["baseBranch"])
        assertTrue(rejected.error)
        assertEquals("invalid-branch", rejected.data["reason"])
    }

    @Test
    fun `omitted settings keep the current values`() {
        val view = AffectedMcpInputs.applySettings(current)
        assertFalse(view.error)
        assertEquals("main", view.data["baseBranch"])
        assertEquals(false, view.data["checkConsumers"])
        assertEquals(false, view.data["runBeforeCommit"])
        assertEquals(true, view.data["runBeforePush"])
        assertEquals(true, view.data["animateWhileRunning"])
        assertTrue("consumer check: off" in view.text)
        assertTrue("commit guard: off" in view.text)
        assertTrue("push guard: on" in view.text)
        assertTrue("animation: on" in view.text)
    }

    @Test
    fun `each setting can be overridden without touching the others`() {
        val view = AffectedMcpInputs.applySettings(
            current,
            baseBranch = "develop",
            checkConsumers = true,
            runBeforeCommit = true,
            runBeforePush = false,
            animateWhileRunning = false,
        )
        assertFalse(view.error)
        assertEquals("develop", view.data["baseBranch"])
        assertEquals(true, view.data["checkConsumers"])
        assertEquals(false, view.data["runBeforePush"])
        assertEquals(false, view.data["animateWhileRunning"])
        assertTrue("consumer check: on" in view.text)
        assertTrue("push guard: off" in view.text)
        assertTrue("animation: off" in view.text)
    }

    private fun snapshot(
        analysisStatus: AnalysisStatus,
        modules: List<AffectedModule> = emptyList(),
    ) = AffectedStateSnapshot(
        revision = 1,
        analysisStatus = analysisStatus,
        modules = modules,
        verificationStatus = VerificationStatus.IDLE,
        changes = null,
        plans = null,
    )

    private fun module(id: String, tasks: Set<String>) = AffectedModule(
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
}
