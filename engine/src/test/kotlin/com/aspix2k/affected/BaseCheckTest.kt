package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BaseCheckTest {

    private fun group(root: String = "/project/app") = TaskGroup("GRADLE", root, listOf(":app:test"))

    private fun verdict(run: BaseRun) = BaseGroupVerdict("GRADLE", ".", listOf(":app:test"), run)

    @Test
    fun `a group that fails in the change and passes on the base is a regression`() {
        assertEquals(BaseVerdict.REGRESSION, verdict(BaseRun.Finished(passed = true)).verdict)
        assertNull(verdict(BaseRun.Finished(passed = true)).reason)
    }

    @Test
    fun `a group that fails on the base too was failing before the change`() {
        assertEquals(BaseVerdict.PRE_EXISTING, verdict(BaseRun.Finished(passed = false)).verdict)
    }

    @Test
    fun `a run that did not complete is never reported as failing before the change`() {
        BaseNotRun.entries.forEach { reason ->
            val skipped = verdict(BaseRun.Skipped(reason))

            assertEquals(BaseVerdict.UNKNOWN, skipped.verdict, reason.name)
            assertEquals(reason, skipped.reason)
        }
    }

    @Test
    fun `only groups that failed are kept from the last verification`() {
        val passed = group("/project/lib")
        val failed = group("/project/app")
        val record = VerificationRecord(
            listOf(GroupResult(passed, passed = true), GroupResult(failed, passed = false)),
            BuildChanges(emptyList(), emptySet(), comparedToBase = true, baseCommit = "a".repeat(40)),
        )

        assertEquals(listOf(failed), record.failed)
        assertEquals(emptyList(), VerificationRecord(listOf(GroupResult(passed, true)), record.changes).failed)
    }

    private fun path(root: String, vararg parts: String): String =
        Path.of(root, *parts).toAbsolutePath().normalize().invariantSeparatorsPathString

    private val project = path("project")
    private val base = path("base")

    @Test
    fun `execution roots keep their path relative to the project`() {
        assertEquals(path("base", "app", "lib"), translateToBase(path("project", "app", "lib"), project, base))
        assertEquals(base, translateToBase(project, project, base))
        assertEquals(path("base", "app"), translateToBase("$project/app/../app", project, base))
        assertEquals(
            path("cache", "base", "sub", "app"),
            translateToBase(path("repo", "sub", "app"), path("repo", "sub"), path("cache", "base", "sub")),
        )
    }

    @Test
    fun `a path outside the project is not translated`() {
        assertNull(translateToBase(path("elsewhere", "app"), project, base))
        assertNull(translateToBase(path("project-other", "app"), project, base))
        assertNull(translateToBase("$project/../outside", project, base))
    }

    @Test
    fun `roots are reported relative to the project`() {
        assertEquals("app/lib", relativeToProject(path("project", "app", "lib"), project))
        assertEquals(".", relativeToProject(project, project))
    }

    @Test
    fun `changes are translated together with the files that make an exact selection`() {
        val inApp = path("project", "app", "A.kt")
        val outside = path("outside", "C.kt")
        val changes = BuildChanges(
            files = listOf(inApp, path("project", "B.kt"), outside),
            exactSelectionEligible = setOf(inApp, outside),
            comparedToBase = true,
            baseCommit = "b".repeat(40),
            baseBranch = "main",
        )

        assertEquals(
            changes.copy(
                files = listOf(path("base", "app", "A.kt"), path("base", "B.kt")),
                exactSelectionEligible = setOf(path("base", "app", "A.kt")),
            ),
            changes.translatedToBase(project, base),
        )
    }

    @Test
    fun `the base commit and branch are shown together`() {
        val commit = "c".repeat(40)

        assertEquals("main ${"c".repeat(12)}", BaseCheckReport(baseCommit = commit, baseBranch = "main").comparison)
        assertEquals("c".repeat(12), BaseCheckReport(baseCommit = commit).comparison)
        assertNull(BaseCheckReport().comparison)
    }
}
