package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.multiRootProject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerificationAdapterTest {

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
                    unresolvedFiles = 1,
                ),
            ),
        )
    }

    @Test
    fun `an empty plan passes when the only changes are files that nothing could verify`() {
        assertTrue(
            verificationPassesWithoutWork(
                Verification.Prepared(
                    Plan(emptyList(), tested = 0, compiled = 0),
                    BuildChanges(listOf("/repo/.github/workflows/ci.yml"), emptySet(), comparedToBase = true),
                ),
            ),
        )
    }

    @Test
    fun `a passing run with changes outside every module is unresolved`() {
        val plan = Plan(emptyList(), tested = 1, compiled = 0)

        assertEquals(
            Verification.Outcome(plan, passed = false, Verification.Blocker.UNRESOLVED_CHANGES),
            completedOutcome(plan, passed = true, unresolvedFiles = 1),
        )
        assertEquals(
            Verification.Outcome(plan, passed = true),
            completedOutcome(plan, passed = true, unresolvedFiles = 0),
        )
        assertEquals(
            Verification.Outcome(plan, passed = false),
            completedOutcome(plan, passed = false, unresolvedFiles = 1),
        )
    }

    @Test
    fun `a git repository without a comparison base cannot pass, with or without planned work`() = runBoundedBlocking {
        val changes = BuildChanges(emptyList(), emptySet(), comparedToBase = false)
        val empty = Plan(emptyList(), tested = 0, compiled = 0)
        val planned = Plan(emptyList(), tested = 1, compiled = 0)
        val project = multiRootProject(File("."))

        assertEquals(
            Verification.Outcome(empty, passed = false, Verification.Blocker.NO_COMPARISON_BASE),
            Verification.runAndWait(project, Verification.Prepared(empty, changes, baseUnresolved = true)),
        )
        assertEquals(
            Verification.Outcome(empty, passed = true),
            Verification.runAndWait(project, Verification.Prepared(empty, changes)),
        )
        assertEquals(
            Verification.Outcome(planned, passed = false, Verification.Blocker.NO_COMPARISON_BASE),
            completedOutcome(planned, passed = true, unresolvedFiles = 0, baseUnresolved = true),
        )
        assertEquals(
            Verification.Outcome(planned, passed = false),
            completedOutcome(planned, passed = false, unresolvedFiles = 0, baseUnresolved = true),
        )
    }
}
