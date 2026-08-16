package com.aspix2k.affected.build

import com.aspix2k.affected.TaskGroup
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecutionRootGuardTest {

    @Test
    fun `an unchanged nested execution root remains valid`() {
        val project = createTempDirectory("execution-root-project")
        val root = project.resolve("-модуль с пробелом").createDirectory()
        val guard = PlannedExecutionRoot.capture(root).bind(project)

        assertNull(guard.validationFailure())
    }

    @Test
    fun `changes inside an execution root do not change its identity`() {
        val project = createTempDirectory("execution-root-contents")
        val root = project.resolve("module").createDirectory()
        val guard = PlannedExecutionRoot.capture(root).bind(project)
        Files.writeString(root.resolve("new-test.txt"), "test")

        assertNull(guard.validationFailure())
    }

    @Test
    fun `a re-created execution root does not retain its planned identity`() {
        val project = createTempDirectory("execution-root-recreated")
        val root = project.resolve("module").createDirectory()
        val guard = PlannedExecutionRoot.capture(root).bind(project)
        Files.delete(root)
        Files.createDirectory(root)

        assertContains(assertNotNull(guard.validationFailure()), "changed since planning")
    }

    @Test
    fun `a root changed between identity reads fails closed`() {
        val project = createTempDirectory("execution-root-inspection-race")
        val root = project.resolve("module").createDirectory()

        val planned = PlannedExecutionRoot.capture(root) {
            Files.delete(root)
            Files.createDirectory(root)
        }

        assertContains(assertNotNull(planned.bind(project).validationFailure()), "changed")
    }

    @Test
    fun `a missing execution root fails closed`() {
        val project = createTempDirectory("execution-root-missing")
        val missing = project.resolve("missing")
        val missingGuard = PlannedExecutionRoot.capture(missing).bind(project)

        assertContains(assertNotNull(missingGuard.validationFailure()), "does not exist")
    }

    @Test
    fun `an unreadable execution root fails closed`() {
        val project = createTempDirectory("execution-root-unreadable")
        val unreadable = project.resolve("unreadable").createDirectory()
        val permissions = runCatching {
            Files.getPosixFilePermissions(unreadable, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull()
        assumeTrue(permissions != null)
        val unreadableGuard = PlannedExecutionRoot.capture(unreadable).bind(project)
        try {
            Files.setPosixFilePermissions(unreadable, setOf(PosixFilePermission.OWNER_WRITE))
            assumeTrue(!Files.isReadable(unreadable))
            assertContains(assertNotNull(unreadableGuard.validationFailure()), "not readable")
        } finally {
            Files.setPosixFilePermissions(unreadable, permissions)
        }
    }

    @Test
    fun `an execution root outside the project fails closed`() {
        val project = createTempDirectory("execution-root-links")
        val outside = createTempDirectory("execution-root-outside")
        val outsideGuard = PlannedExecutionRoot.capture(outside).bind(project)
        assertContains(assertNotNull(outsideGuard.validationFailure()), "outside the opened project")
    }

    @Test
    fun `final intermediate and dangling links fail closed`() {
        val project = createTempDirectory("execution-root-links")
        val outside = createTempDirectory("execution-root-outside")
        val finalLink = project.resolve("final-link")
        assumeTrue(runCatching { Files.createSymbolicLink(finalLink, outside) }.isSuccess)
        assertContains(
            assertNotNull(PlannedExecutionRoot.capture(finalLink).bind(project).validationFailure()),
            "link",
        )

        val real = project.resolve("real").createDirectory()
        real.resolve("nested").createDirectory()
        val intermediate = project.resolve("intermediate")
        Files.createSymbolicLink(intermediate, real)
        assertContains(
            assertNotNull(
                PlannedExecutionRoot.capture(intermediate.resolve("nested")).bind(project).validationFailure(),
            ),
            "link",
        )

        val dangling = project.resolve("dangling")
        Files.createSymbolicLink(dangling, project.resolve("absent"))
        assertContains(
            assertNotNull(PlannedExecutionRoot.capture(dangling).bind(project).validationFailure()),
            "link",
        )
    }

    @Test
    fun `active planning preserves one identity for concurrent groups`() = kotlinx.coroutines.runBlocking {
        val project = createTempDirectory("execution-root-active")
        val root = project.resolve("module").createDirectory()
        val planned = PlannedExecutionRoot.capture(root)

        withPlannedExecutionRoot(planned, project) {
            withPlannedExecutionRoot(planned, project) {
                assertNull(executionRootGuard(root, project).validationFailure())
            }
        }
    }

    @Test
    fun `active planning does not recapture a replaced root`() = kotlinx.coroutines.runBlocking {
        val project = createTempDirectory("execution-root-active-replaced")
        val root = project.resolve("module").createDirectory()
        val planned = PlannedExecutionRoot.capture(root)

        withPlannedExecutionRoot(planned, project) {
            Files.delete(root)
            Files.createDirectory(root)
            assertContains(
                assertNotNull(executionRootGuard(root, project).validationFailure()),
                "changed since planning",
            )
        }
    }

    @Test
    fun `a named task group retains the identity captured by its plan`() = kotlinx.coroutines.runBlocking {
        val project = createTempDirectory("execution-root-named-group")
        val root = project.resolve("module").createDirectory()
        val group = TaskGroup("CMAKE", root.toString(), listOf(".:test"))
        Files.delete(root)
        Files.createDirectory(root)

        group.runInPlannedExecutionRoot(project) {
            assertContains(
                assertNotNull(projectExecutionRootGuard(root, project).validationFailure()),
                "changed since planning",
            )
        }
    }

    @Test
    fun `a task group does not accept a root created after planning`() = kotlinx.coroutines.runBlocking {
        val project = createTempDirectory("execution-root-created-late")
        val root = project.resolve("module")
        val group = TaskGroup("CMAKE", root.toString(), listOf(".:test"))
        Files.createDirectory(root)

        group.runInPlannedExecutionRoot(project) {
            assertContains(
                assertNotNull(projectExecutionRootGuard(root, project).validationFailure()),
                "does not exist",
            )
        }
    }

    @Test
    fun `a replaced task group root stops before adapter invocation`() = kotlinx.coroutines.runBlocking {
        val project = createTempDirectory("execution-root-pre-adapter")
        val root = project.resolve("module").createDirectory()
        val group = TaskGroup("CMAKE", root.toString(), listOf(".:test"))
        Files.delete(root)
        Files.createDirectory(root)
        var refused = false
        var invoked = false

        val passed = group.runInPlannedExecutionRoot(project, onInvalid = { refused = true }) {
            invoked = true
            true
        }

        assertFalse(passed)
        assertTrue(refused)
        assertFalse(invoked)
    }

    @Test
    fun `an active root identity is not borrowed by another opened project`() = kotlinx.coroutines.runBlocking {
        val project = createTempDirectory("execution-root-active-project")
        val otherProject = createTempDirectory("execution-root-other-project")
        val root = project.resolve("module").createDirectory()
        val planned = PlannedExecutionRoot.capture(root)

        withPlannedExecutionRoot(planned, project) {
            assertContains(
                assertNotNull(projectExecutionRootGuard(root, otherProject).validationFailure()),
                "another opened project",
            )
        }
    }

    @Test
    fun `an active plan governs metadata children`() = kotlinx.coroutines.runBlocking {
        val project = createTempDirectory("execution-root-active-child")
        val root = project.resolve("module").createDirectory()
        val child = root.resolve("metadata").createDirectory()
        val planned = PlannedExecutionRoot.capture(root)

        withPlannedExecutionRoot(planned, project) {
            assertNull(executionRootGuard(child).validationFailure())
        }
    }

    @Test
    fun `an exact nested plan wins over an active parent plan`() = kotlinx.coroutines.runBlocking {
        val project = createTempDirectory("execution-root-overlap")
        val parent = project.resolve("workspace").createDirectory()
        val child = parent.resolve("backend").createDirectory()
        val parentPlan = PlannedExecutionRoot.capture(parent)
        val childPlan = PlannedExecutionRoot.capture(child)

        withPlannedExecutionRoot(parentPlan, project) {
            withPlannedExecutionRoot(childPlan, project) {
                Files.delete(child)
                Files.createDirectory(child)
                assertContains(
                    assertNotNull(projectExecutionRootGuard(child, project).validationFailure()),
                    "changed since planning",
                )
            }
        }
    }

    @Test
    fun `a plan context follows coroutine hops and rejects a retargeted canonical root`() =
        kotlinx.coroutines.runBlocking {
            val project = createTempDirectory("execution-root-context-hop")
            val root = project.resolve("module").createDirectory()
            val outside = createTempDirectory("execution-root-context-outside")
            val group = TaskGroup("CMAKE", root.toString(), listOf(".:test"))

            group.runInPlannedExecutionRoot(project) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Files.delete(root)
                    assumeTrue(runCatching { Files.createSymbolicLink(root, outside) }.isSuccess)
                    assertContains(
                        assertNotNull(executionRootGuard(outside).validationFailure()),
                        "link",
                    )
                }
            }
        }
}
