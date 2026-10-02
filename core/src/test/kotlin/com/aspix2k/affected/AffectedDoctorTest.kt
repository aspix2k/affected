package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AffectedDoctorTest {

    @Test
    fun `a missing git repository is a problem and hides base findings`() {
        val findings = diagnose(snapshot(changes = changes(gitUsable = false)))

        assertEquals(DoctorSeverity.PROBLEM, findings.single { it.id == "git-unavailable" }.severity)
        assertEquals(listOf("/repo"), findings.single { it.id == "git-unavailable" }.args)
        assertTrue(findings.none { it.id.startsWith("base-") })
    }

    @Test
    fun `the resolved comparison base is named and an unresolved one is a problem`() {
        val resolved = diagnose(snapshot(changes = changes(resolvedBranch = "main")))
        val unresolved = diagnose(snapshot(changes = changes(baseUnresolved = true)), baseBranch = "develop")
        val fresh = diagnose(snapshot(changes = changes()))

        assertEquals(listOf("main"), resolved.single { it.id == "base-resolved" }.args)
        assertTrue(resolved.none { it.id == "base-unresolved" })
        assertEquals(DoctorSeverity.PROBLEM, unresolved.single { it.id == "base-unresolved" }.severity)
        assertEquals(listOf("develop"), unresolved.single { it.id == "base-unresolved" }.args)
        assertTrue(fresh.none { it.id.startsWith("base-") })
    }

    @Test
    fun `present systems are listed with their module and root counts`() {
        val snapshot = snapshot(systems = listOf(BuildSystemSummary("GRADLE", modules = 12, roots = 2)))

        val findings = diagnose(snapshot, present = listOf(DoctorSystem("GRADLE", null, false), system("CARGO")))

        assertEquals(listOf("GRADLE 12 / 2, CARGO 0 / 0"), findings.single { it.id == "build-systems" }.args)
        assertTrue(findings.none { it.id == "no-build-system" })
    }

    @Test
    fun `no build system and an unimported one are told apart`() {
        val none = diagnose(snapshot())
        val unimported = diagnose(snapshot(), notImported = listOf("GRADLE", "MAVEN"))

        assertEquals(DoctorSeverity.PROBLEM, none.single { it.id == "no-build-system" }.severity)
        assertTrue(none.none { it.id == "build-systems" })
        assertEquals(
            listOf(listOf("GRADLE"), listOf("MAVEN")),
            unimported.filter { it.id == "build-system-not-imported" }.map(DoctorFinding::args),
        )
        assertTrue(unimported.none { it.id == "no-build-system" })
    }

    @Test
    fun `tools missing from the path are a problem and found ones are ok`() {
        val missing = diagnose(
            snapshot(),
            present = listOf(system("CARGO"), system("GO", found = false), DoctorSystem("GRADLE", null, false)),
        )
        val ready = diagnose(snapshot(), present = listOf(system("CARGO"), DoctorSystem("GRADLE", null, false)))
        val unchecked = diagnose(snapshot(), present = listOf(DoctorSystem("GRADLE", null, false)))

        assertEquals(listOf("go"), missing.single { it.id == "tools-missing" }.args)
        assertTrue(missing.none { it.id == "tools-ready" })
        assertEquals(listOf("cargo"), ready.single { it.id == "tools-ready" }.args)
        assertTrue(unchecked.none { it.id.startsWith("tools-") })
    }

    @Test
    fun `reached discovery limits and the module budget are reported`() {
        val capped = diagnose(snapshot(), rootsCapped = true)
        val over = diagnose(snapshot(analysisStatus = AnalysisStatus.UNAVAILABLE, overBudget = true))

        assertEquals(DoctorSeverity.WARNING, capped.single { it.id == "nested-roots-capped" }.severity)
        assertEquals(DoctorSeverity.PROBLEM, over.single { it.id == "modules-over-budget" }.severity)
        assertTrue(over.none { it.id == "analysis-unavailable" })
    }

    @Test
    fun `changes count unresolved and uncovered files with at most five relative examples`() {
        val unresolved = (1..7).map { File("/repo/lib/U$it.kt") }
        val findings = diagnose(
            snapshot(
                changes = changes(
                    files = unresolved + File("/repo/App.kt"),
                    uncovered = listOf(File("/elsewhere/x.py")),
                ),
                plans = Verification.PreparedPlans(prepared(unresolved), prepared(unresolved)),
            ),
        )

        assertEquals(listOf("8"), findings.single { it.id == "changes-found" }.args)
        assertEquals(
            listOf("7", "lib/U1.kt, lib/U2.kt, lib/U3.kt, lib/U4.kt, lib/U5.kt"),
            findings.single { it.id == "changes-unresolved" }.args,
        )
        assertEquals(listOf("1", "/elsewhere/x.py"), findings.single { it.id == "changes-uncovered" }.args)
    }

    @Test
    fun `a clean change set has no warnings`() {
        val findings =
            diagnose(snapshot(changes = changes(), plans = Verification.PreparedPlans(prepared(), prepared())))

        assertEquals(listOf("0"), findings.single { it.id == "changes-found" }.args)
        assertTrue(findings.none { it.id.startsWith("changes-") && it.severity != DoctorSeverity.OK })
    }

    @Test
    fun `analysis state and a busy IDE are reported`() {
        val analyzing = diagnose(snapshot(analysisStatus = AnalysisStatus.ANALYZING))
        val unavailable = diagnose(snapshot(analysisStatus = AnalysisStatus.UNAVAILABLE))
        val ready = diagnose(snapshot(), ideBusy = true)

        assertEquals(DoctorSeverity.WARNING, analyzing.single { it.id == "analysis-analyzing" }.severity)
        assertEquals(DoctorSeverity.PROBLEM, unavailable.single { it.id == "analysis-unavailable" }.severity)
        assertEquals(DoctorSeverity.OK, ready.single { it.id == "analysis-ready" }.severity)
        assertEquals(DoctorSeverity.WARNING, ready.single { it.id == "ide-busy" }.severity)
        assertTrue(analyzing.none { it.id == "changes-found" })
    }

    @Test
    fun `every finding renders a message and a remedy without leftover placeholders`() {
        val everything = AffectedDoctor.texts.keys.map { DoctorFinding(it, DoctorSeverity.WARNING, listOf("a", "b")) }

        for (finding in everything) {
            assertFalse(finding.message.contains('{'), finding.id)
            assertTrue(finding.remedy.isNotBlank(), finding.id)
        }
        assertEquals("Nothing to do.", DoctorFinding("analysis-ready", DoctorSeverity.OK).remedy)
        assertNull(AffectedDoctor.texts.getValue("analysis-ready").remedy)
    }

    @Test
    fun `the doctor view summarizes severity and lists every finding`() {
        val view = AffectedDoctor.view(
            listOf(
                DoctorFinding("analysis-ready", DoctorSeverity.OK),
                DoctorFinding("ide-busy", DoctorSeverity.WARNING),
                DoctorFinding("no-build-system", DoctorSeverity.PROBLEM),
            ),
        )

        assertFalse(view.error)
        assertEquals("problem", view.data["status"])
        assertEquals(1, view.data["problems"])
        assertEquals(1, view.data["warnings"])
        assertEquals(listOf("analysis-ready", "ide-busy", "no-build-system"), view.findingIds())
        assertTrue(view.text.startsWith("Doctor: 1 problem(s), 1 warning(s)."))
        assertEquals("ok", AffectedDoctor.view(emptyList()).data["status"])
    }

    private fun AffectedMcpView.findingIds(): List<Any?> =
        (data["findings"] as List<*>).map { (it as Map<*, *>)["id"] }

    private fun diagnose(
        snapshot: AffectedStateSnapshot,
        present: List<DoctorSystem> = emptyList(),
        notImported: List<String> = emptyList(),
        rootsCapped: Boolean = false,
        ideBusy: Boolean = false,
        baseBranch: String = "main",
    ) = AffectedDoctor.diagnose(
        DoctorInput(snapshot, "/repo", baseBranch, present, notImported, rootsCapped, ideBusy),
    )

    private fun system(id: String, found: Boolean = true) =
        DoctorSystem(id, id.lowercase(), found)

    private fun snapshot(
        analysisStatus: AnalysisStatus = AnalysisStatus.READY,
        changes: ProjectChanges.Result? = null,
        plans: Verification.PreparedPlans? = null,
        systems: List<BuildSystemSummary> = emptyList(),
        overBudget: Boolean = false,
    ) = AffectedStateSnapshot(
        revision = 1,
        analysisStatus = analysisStatus,
        modules = emptyList(),
        verificationStatus = VerificationStatus.IDLE,
        changes = changes,
        plans = plans,
        systems = systems,
        overBudget = overBudget,
    )

    private fun changes(
        files: List<File> = emptyList(),
        uncovered: List<File> = emptyList(),
        gitUsable: Boolean = true,
        resolvedBranch: String? = null,
        baseUnresolved: Boolean = false,
    ) = ProjectChanges.Result(
        files = files,
        apiTouched = emptySet(),
        exactSelectionEligible = emptySet(),
        comparedToBase = !baseUnresolved && gitUsable,
        baseUnresolved = baseUnresolved,
        uncovered = uncovered,
        gitUsable = gitUsable,
        resolvedBranch = resolvedBranch,
    )

    private fun prepared(unresolved: List<File> = emptyList()) = Verification.Prepared(
        plan = Plan(emptyList(), 0, 0),
        changes = BuildChanges(emptyList(), emptySet(), comparedToBase = true),
        unresolvedFiles = unresolved.size,
        unresolved = unresolved,
    )
}
