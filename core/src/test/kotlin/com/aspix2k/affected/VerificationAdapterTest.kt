package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerificationAdapterTest {

    @Test
    fun `a missing adapter fails the prepared group`() {
        assertFalse(preparedGroupPasses(adapterFound = false))
    }

    @Test
    fun `a present adapter is allowed to run`() {
        assertTrue(preparedGroupPasses(adapterFound = true))
    }

    @Test
    fun `a named task or toolbar check fails when the adapter is gone`() {
        assertFalse(runWithRequiredAdapter(null as String?) { true })
    }

    @Test
    fun `a named task still reports the adapter result`() {
        assertTrue(runWithRequiredAdapter("gradle") { true })
        assertFalse(runWithRequiredAdapter("gradle") { false })
    }

    @Test
    fun `an empty plan with no changes is successful`() {
        assertTrue(
            verificationPassesWithoutWork(
                Verification.Prepared(
                    Plan(emptyList(), tested = 0, compiled = 0),
                    BuildChanges(emptyList(), emptySet(), comparedToBase = false),
                ),
            ),
        )
    }

    @Test
    fun `an empty plan with unresolved changes is not successful`() {
        assertFalse(
            verificationPassesWithoutWork(
                Verification.Prepared(
                    Plan(emptyList(), tested = 0, compiled = 0),
                    BuildChanges(listOf("/repo/src/Main.kt"), emptySet(), comparedToBase = true),
                ),
            ),
        )
    }
}
