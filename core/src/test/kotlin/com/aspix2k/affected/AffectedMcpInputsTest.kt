package com.aspix2k.affected

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
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
            testDependents = true,
        )
        assertFalse(view.error)
        assertEquals("develop", view.data["baseBranch"])
        assertEquals(true, view.data["checkConsumers"])
        assertEquals(true, view.data["testDependents"])
        assertTrue("dependents' tests: on" in view.text)
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

    @Test
    fun `an empty or auto base branch clears the setting`() {
        listOf("", "  ", "auto").forEach { input ->
            val validated = AffectedMcpInputs.validateBaseBranch(input)
            assertFalse(validated.error)
            assertEquals("auto", validated.data["baseBranch"])
            assertEquals("auto", AffectedMcpInputs.applySettings(current, baseBranch = input).data["baseBranch"])
        }
    }

    @Test
    fun `the resolved branch is reported until the base branch changes`() {
        val automatic = current.copy(baseBranch = "auto", resolvedBaseBranch = "main")

        val kept = AffectedMcpInputs.applySettings(automatic, checkConsumers = true)
        val changed = AffectedMcpInputs.applySettings(automatic, baseBranch = "release")

        assertEquals("main", kept.data["resolvedBaseBranch"])
        assertTrue("Base branch: auto (main)," in kept.text)
        assertEquals("release", changed.data["baseBranch"])
        assertEquals(null, changed.data["resolvedBaseBranch"])
        assertTrue("Base branch: release," in changed.text)
    }

    @Test
    fun `a source file inside the project is accepted and reported relative to the base`() = withProject { base ->
        val file = Files.createDirectories(base.resolve("src")).resolve("Repo.kt")
        Files.writeString(file, "class Repo")

        val view = AffectedMcpInputs.validateSourceFile(base.toString(), " src/Repo.kt ")

        assertFalse(view.error)
        assertEquals("src/Repo.kt", view.data["file"])
        assertEquals(file.toRealPath().toString(), view.data["path"])
    }

    @Test
    fun `paths that are empty missing outside the project or not JVM sources are rejected with a reason`() =
        withProject { base ->
            Files.writeString(base.resolve("Notes.md"), "x")
            Files.createDirectory(base.resolve("Folder.kt"))
            val outside = createTempDirectory("affected-outside-").resolve("Other.kt")
            Files.writeString(outside, "class Other")
            val link = base.resolve("Link.kt")
            Files.createSymbolicLink(link, outside)

            fun reason(path: String) = AffectedMcpInputs.validateSourceFile(base.toString(), path).data["reason"]

            assertEquals("invalid-path", reason("   "))
            assertEquals("invalid-path", reason("a".repeat(5000)))
            assertEquals("invalid-path", reason("bad\u0000.kt"))
            assertEquals("file-not-found", reason("Missing.kt"))
            assertEquals("file-not-found", reason("a".repeat(4096)))
            assertEquals("invalid-path", reason("a".repeat(4097)))
            assertEquals("file-not-found", reason("Folder.kt"))
            assertEquals("outside-project", reason(outside.toString()))
            assertEquals("outside-project", reason(base.relativize(outside).toString()))
            assertEquals("outside-project", reason("Link.kt"))
            assertEquals("unsupported-file", reason("Notes.md"))
            val gone = AffectedMcpInputs.validateSourceFile(base.resolve("gone").toString(), "A.kt")
            assertEquals("no-base-path", gone.data["reason"])
            outside.parent.toFile().deleteRecursively()
        }

    private fun withProject(block: (Path) -> Unit) {
        val base = createTempDirectory("affected-project-").toRealPath()
        try {
            block(base)
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}
