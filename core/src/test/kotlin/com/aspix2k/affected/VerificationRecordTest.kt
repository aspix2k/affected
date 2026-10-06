package com.aspix2k.affected

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals

class VerificationRecordTest {

    private val group = TaskGroup("GRADLE", "/project", listOf(":app:test"))

    private fun claim() = AffectedRunClaim(
        snapshot = AffectedStateSnapshot(
            revision = 1,
            analysisStatus = AnalysisStatus.READY,
            modules = emptyList(),
            verificationStatus = VerificationStatus.RUNNING,
        ),
        changes = null,
        prepared = null,
        markRunning = { true },
        release = {},
    )

    @Test
    fun `a failure of a running verification is recorded`() {
        val results = mutableListOf<GroupResult>()

        results.recordGroup(claim(), group, passed = false)

        assertEquals(listOf(GroupResult(group, passed = false)), results)
    }

    @Test
    fun `a group stopped by the user is not recorded as failed but a pass is`() {
        val claim = claim()
        val results = mutableListOf<GroupResult>()
        claim.stopIfActive()

        results.recordGroup(claim, group, passed = false)
        results.recordGroup(claim, group, passed = true)

        assertEquals(listOf(GroupResult(group, passed = true)), results)
    }

    @Test
    fun `a group stopped because another one failed first is not recorded as failed`() {
        val claim = claim()
        val results = mutableListOf<GroupResult>()
        claim.failFast(Job())

        results.recordGroup(claim, group, passed = false)

        assertEquals(emptyList(), results)
    }
}
