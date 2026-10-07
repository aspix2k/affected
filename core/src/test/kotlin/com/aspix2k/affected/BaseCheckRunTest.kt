package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.BuildSystem
import com.aspix2k.affected.build.SuspendingBuildSystem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

class BaseCheckRunTest : BasePlatformTestCase() {

    private var registeredPoint = false

    override fun setUp() {
        super.setUp()
        val area = ApplicationManager.getApplication().extensionArea
        if (!area.hasExtensionPoint(BUILD_SYSTEM_POINT)) {
            area.registerExtensionPoint(
                BUILD_SYSTEM_POINT.name,
                BuildSystem::class.java.name,
                ExtensionPoint.Kind.INTERFACE,
                true,
            )
            registeredPoint = true
        }
    }

    override fun tearDown() {
        try {
            super.tearDown()
        } finally {
            if (registeredPoint) {
                ApplicationManager.getApplication().extensionArea.unregisterExtensionPoint(BUILD_SYSTEM_POINT.name)
            }
        }
    }

    override fun runInDispatchThread(): Boolean = false

    private class Call(val root: String, val tasks: List<String>, val recordsDependencies: Boolean)

    private class RecordingMaven(
        private val root: File,
        private val passes: (String) -> Boolean,
    ) : SuspendingBuildSystem {
        val calls = ConcurrentLinkedQueue<Call>()

        override val id: String = "MAVEN"
        override val sourceExtensions: Set<String> = setOf("kt")
        override fun isPresent(project: Project): Boolean = true
        override fun modules(project: Project): List<BuildModule> = listOf(
            module("mod-a", File(root, "mod-a")),
            module("mod-new", File(root, "mod-new")),
        )

        private fun module(id: String, directory: File) = BuildModule(
            id = id,
            root = root.path,
            contentRoots = listOf(directory.path),
            testTask = "test",
            compileTask = null,
            hasTests = true,
            executionRoot = root.path,
            executionId = id,
        )

        override fun run(project: Project, root: String, tasks: List<String>) = Unit

        override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean {
            calls += Call(root, tasks, recordsDependencies())
            return passes(root)
        }
    }

    private class Repository(val root: File, val base: String)

    private fun repository(): Repository {
        val root = File(requireNotNull(project.basePath)).apply { mkdirs() }
        cacheCheckout().deleteRecursively()
        root.listFiles { file -> file.name == ".git" || file.name.startsWith("mod-") || file.extension == "kt" }
            .orEmpty()
            .forEach(File::deleteRecursively)
        FixtureRepository.git(root, "init", "-q", "-b", "main")
        FixtureRepository.git(root, "config", "user.email", "t@e.com")
        FixtureRepository.git(root, "config", "user.name", "t")
        File(root, "mod-a").mkdirs()
        File(root, "mod-b").mkdirs()
        File(root, "mod-a/Main.kt").writeText("base\n")
        File(root, "mod-b/Main.kt").writeText("base\n")
        FixtureRepository.git(root, "add", "-A")
        FixtureRepository.git(root, "commit", "-qm", "base")
        val base = FixtureRepository.git(root, "rev-parse", "HEAD").trim()
        File(root, "mod-new").mkdirs()
        File(root, "mod-new/Main.kt").writeText("added\n")
        File(root, "mod-a/Main.kt").writeText("changed\n")
        FixtureRepository.git(root, "add", "-A")
        FixtureRepository.git(root, "commit", "-qm", "change")
        return Repository(root, base)
    }

    private fun group(root: File, vararg tasks: String, system: String = "MAVEN") =
        TaskGroup(system, root.path, tasks.toList())

    private fun record(repository: Repository, vararg groups: TaskGroup, baseCommit: String? = repository.base) =
        VerificationRecord(
            groups.map { GroupResult(it, passed = false) },
            BuildChanges(emptyList(), emptySet(), comparedToBase = true, baseCommit = baseCommit, baseBranch = "main"),
        )

    private fun cacheCheckout(): File =
        PathManager.getSystemDir().resolve("affected").resolve(project.locationHash).resolve("base").toFile()

    private fun userState(root: File): List<String> = listOf(
        FixtureRepository.git(root, "rev-parse", "HEAD"),
        FixtureRepository.git(root, "status", "--short"),
        FixtureRepository.git(root, "worktree", "list"),
    )

    fun testEachFailedGroupGetsItsVerdictFromARunInTheBaseCheckout() = runBoundedBlocking {
        val repository = repository()
        val root = repository.root
        val adapter = RecordingMaven(root) { it.endsWith("/mod-a") }
        ExtensionTestUtil.maskExtensions(BUILD_SYSTEM_POINT, listOf(adapter), testRootDisposable)
        File(root, "Uncommitted.kt").writeText("local\n")
        val before = userState(root)
        val regression = group(File(root, "mod-a"), "mod-a:test")
        val failingBefore = group(File(root, "mod-b"), "mod-b:test")
        val newModule = group(root, "mod-new:test")
        val addedRoot = group(File(root, "mod-added"), "mod-added:test")
        val foreign = group(root, "test", system = "NODE")

        val report = BaseCheck.run(
            project,
            record(repository, regression, failingBefore, newModule, addedRoot, foreign),
        )

        assertNull(report.blocker)
        assertEquals(repository.base, report.baseCommit)
        assertEquals(
            listOf(
                BaseGroupVerdict("MAVEN", "mod-a", listOf("mod-a:test"), BaseRun.Finished(passed = true)),
                BaseGroupVerdict("MAVEN", "mod-b", listOf("mod-b:test"), BaseRun.Finished(passed = false)),
                BaseGroupVerdict("MAVEN", ".", listOf("mod-new:test"), BaseRun.Skipped(BaseNotRun.MODULE_MISSING)),
                BaseGroupVerdict(
                    "MAVEN",
                    "mod-added",
                    listOf("mod-added:test"),
                    BaseRun.Skipped(BaseNotRun.MODULE_MISSING),
                ),
                BaseGroupVerdict("NODE", ".", listOf("test"), BaseRun.Skipped(BaseNotRun.UNSUPPORTED_SYSTEM)),
            ),
            report.verdicts,
        )
        val checkout = cacheCheckout().canonicalPath
        assertEquals(
            setOf("$checkout/mod-a", "$checkout/mod-b"),
            adapter.calls.mapTo(HashSet()) { File(it.root).canonicalPath },
        )
        assertTrue(adapter.calls.none { it.recordsDependencies })
        assertEquals("base\n", File(cacheCheckout(), "mod-a/Main.kt").readText())
        assertFalse(File(cacheCheckout(), "Uncommitted.kt").exists())
        assertEquals(before, userState(root))
        assertEquals(VerificationStatus.IDLE, project.service<AffectedState>().snapshot().verificationStatus)
        assertEquals(0, AffectedRunSessions.getInstance(project).activeCount())
    }

    fun testARunThatCouldNotStartIsNeverReportedAsFailingBefore() = runBoundedBlocking {
        val repository = repository()
        val adapter = RecordingMaven(repository.root) { false }
        ExtensionTestUtil.maskExtensions(BUILD_SYSTEM_POINT, listOf(adapter), testRootDisposable)
        val failed = group(File(repository.root, "mod-b"), "mod-b:test")

        val withoutBase = BaseCheck.run(project, record(repository, failed, baseCommit = null))
        val missingCommit = BaseCheck.run(project, record(repository, failed, baseCommit = "0".repeat(40)))

        assertEquals(
            listOf(BaseRun.Skipped(BaseNotRun.NO_BASE_COMMIT)),
            withoutBase.verdicts.map(BaseGroupVerdict::run),
        )
        assertEquals(
            listOf(BaseRun.Skipped(BaseNotRun.CHECKOUT_FAILED)),
            missingCommit.verdicts.map(BaseGroupVerdict::run),
        )
        assertTrue(adapter.calls.isEmpty())
    }

    fun testTheCheckNeedsAFailedVerificationAndTheExclusiveSession() = runBoundedBlocking {
        val repository = repository()
        val adapter = RecordingMaven(repository.root) { true }
        ExtensionTestUtil.maskExtensions(BUILD_SYSTEM_POINT, listOf(adapter), testRootDisposable)
        val state = project.service<AffectedState>()

        assertEquals(BaseCheckBlocker.NO_FAILED_VERIFICATION, BaseCheck.run(project).blocker)

        val claim = requireNotNull(state.tryClaimVerification())
        try {
            val busy = BaseCheck.run(project, record(repository, group(File(repository.root, "mod-a"), "mod-a:test")))

            assertEquals(BaseCheckBlocker.NOT_STARTED, busy.blocker)
            assertTrue(adapter.calls.isEmpty())
        } finally {
            claim.close()
        }
    }

    fun testTheLastVerificationKeepsOnlyTheGroupsThatFailed() = runBoundedBlocking {
        val repository = repository()
        val adapter = RecordingMaven(repository.root) { it.endsWith("/mod-a") }
        ExtensionTestUtil.maskExtensions(BUILD_SYSTEM_POINT, listOf(adapter), testRootDisposable)
        val state = project.service<AffectedState>()
        val passing = group(File(repository.root, "mod-a"), "mod-a:test")
        val failing = group(File(repository.root, "mod-b"), "mod-b:test")
        val changes = BuildChanges(emptyList(), emptySet(), comparedToBase = true, baseCommit = repository.base)
        val prepared = Verification.Prepared(Plan(listOf(passing, failing), tested = 2, compiled = 0), changes)

        val outcome = Verification.runClaimedAndWait(project, prepared, requireNotNull(state.tryClaimVerification()))

        assertFalse(outcome.passed)
        val record = requireNotNull(state.lastVerification)
        assertEquals(listOf(passing, failing), record.results.map(GroupResult::group))
        assertEquals(listOf(failing), record.failed)
        assertEquals(changes, record.changes)

        val fixed = Verification.Prepared(Plan(listOf(passing), tested = 1, compiled = 0), changes)
        assertTrue(
            Verification.runClaimedAndWait(project, fixed, requireNotNull(state.tryClaimVerification())).passed,
        )
        assertEquals(emptyList<TaskGroup>(), requireNotNull(state.lastVerification).failed)
    }

    private companion object {
        val BUILD_SYSTEM_POINT = ExtensionPointName.create<BuildSystem>("com.aspix2k.affected.buildSystem")
    }
}
