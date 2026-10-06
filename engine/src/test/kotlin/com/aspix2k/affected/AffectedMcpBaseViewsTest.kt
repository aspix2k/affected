package com.aspix2k.affected

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AffectedMcpBaseViewsTest {

    private val commit = "d".repeat(40)

    private fun verdict(root: String, run: BaseRun) = BaseGroupVerdict("GRADLE", root, listOf(":$root:test"), run)

    @Test
    fun `a completed check lists a verdict and a reason for every group`() {
        val view = AffectedMcpBaseViews.check(
            BaseCheckReport(
                verdicts = listOf(
                    verdict("app", BaseRun.Finished(passed = true)),
                    verdict("lib", BaseRun.Finished(passed = false)),
                    verdict("new", BaseRun.Skipped(BaseNotRun.MODULE_MISSING)),
                ),
                baseCommit = commit,
                baseBranch = "main",
            ),
        )

        assertFalse(view.error)
        assertEquals(
            "Compared with the base (main ${"d".repeat(12)}): " +
                "1 regression(s), 1 failing before your change, 1 unknown.",
            view.text,
        )
        assertEquals(1, view.data["regressions"])
        assertEquals(1, view.data["failingBefore"])
        assertEquals(1, view.data["unknown"])
        assertEquals("main", view.data["baseBranch"])
        assertEquals(commit, view.data["baseCommit"])
        assertEquals(
            listOf(
                mapOf(
                    "system" to "GRADLE",
                    "root" to "app",
                    "tasks" to listOf(":app:test"),
                    "verdict" to "regression",
                    "reason" to null,
                ),
                mapOf(
                    "system" to "GRADLE",
                    "root" to "lib",
                    "tasks" to listOf(":lib:test"),
                    "verdict" to "failing-before-change",
                    "reason" to null,
                ),
                mapOf(
                    "system" to "GRADLE",
                    "root" to "new",
                    "tasks" to listOf(":new:test"),
                    "verdict" to "unknown",
                    "reason" to "module-missing",
                ),
            ),
            view.data["groups"],
        )
    }

    @Test
    fun `an unsupported build system is reported as not supported yet`() {
        val view = AffectedMcpBaseViews.check(
            BaseCheckReport(
                listOf(BaseGroupVerdict("CARGO", ".", listOf("test"), BaseRun.Skipped(BaseNotRun.UNSUPPORTED_SYSTEM))),
            ),
        )

        val group = (view.data["groups"] as List<*>).single() as Map<*, *>
        assertEquals("unknown", group["verdict"])
        assertEquals("not-supported-yet", group["reason"])
    }

    @Test
    fun `nothing to check and a busy session are errors with a reason`() {
        val nothing = AffectedMcpBaseViews.check(BaseCheckReport(blocker = BaseCheckBlocker.NO_FAILED_VERIFICATION))
        val busy = AffectedMcpBaseViews.check(BaseCheckReport(blocker = BaseCheckBlocker.NOT_STARTED))

        assertTrue(nothing.error)
        assertEquals("no-failed-verification", nothing.data["reason"])
        assertTrue(busy.error)
        assertEquals("busy", busy.data["reason"])
    }
}
