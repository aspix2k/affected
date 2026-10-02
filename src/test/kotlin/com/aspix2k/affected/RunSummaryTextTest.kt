package com.aspix2k.affected

import kotlin.test.Test
import kotlin.test.assertEquals

class RunSummaryTextTest {

    private val summary = RunSummary(3, 41, 3, 48_000, 660_000, 0)

    @Test
    fun `a complete estimate is stated without caveats`() {
        assertEquals(
            "Modules tested: 3 of 41, in 48 s. Skipped tasks took about 11 min last time.",
            runSummaryText(summary),
        )
    }

    @Test
    fun `an incomplete estimate says how many skipped tasks have no record`() {
        assertEquals(
            "Modules tested: 3 of 41, in 48 s. Skipped tasks with a recorded time took about 11 min last time. " +
                "Tasks without a record: 5.",
            runSummaryText(summary.copy(skippedWithoutEstimate = 5)),
        )
    }

    @Test
    fun `without any recorded duration no time is claimed`() {
        assertEquals(
            "Modules tested: 3 of 41, in 48 s.",
            runSummaryText(summary.copy(estimatedSavedMillis = null, skippedWithoutEstimate = 38)),
        )
    }
}
