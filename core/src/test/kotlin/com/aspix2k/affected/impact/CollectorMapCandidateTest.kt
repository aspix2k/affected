package com.aspix2k.affected.impact

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectorMapCandidateTest : CollectorMapFixture() {

    @Test
    fun `a valid task output maps every manifest field into the candidate`() = withDirectory { root ->
        val task = task(root)
        worker(task, "worker-1", "AlphaTest", dependency("Alpha", "alpha-1"))
        worker(task, "worker-2", "BetaTest", dependency("Beta", "beta-1"))

        val candidate = assertNotNull(CollectorMapReader.read(task, "collector-1", "run-1"))
        val cancelled = assertNotNull(CollectorMapReader.read(task, "collector-1", "run-1", cancelled = true))
        val failed = assertNotNull(CollectorMapReader.read(task, "collector-1", "run-1", failed = true))

        assertEquals(
            DependencyMapIdentity(
                DEPENDENCY_MAP_SCHEMA_VERSION,
                "collector-1",
                "root|:app|testDebugUnitTest",
                "runtime-1",
                "input-1",
            ),
            candidate.identity,
        )
        assertEquals(listOf(dependency("Alpha", "alpha-1"), dependency("Beta", "beta-1")), candidate.artifacts)
        assertEquals("run-1", candidate.completedRunId)
        assertTrue(candidate.collectsAllTests)
        assertFalse(candidate.cancelled)
        assertFalse(candidate.failed)
        assertEquals(
            mapOf(
                "worker-1" to listOf(record(testClass("AlphaTest"), dependency("Alpha", "alpha-1"))),
                "worker-2" to listOf(record(testClass("BetaTest"), dependency("Beta", "beta-1"))),
            ),
            candidate.workers.associate { it.workerId to it.records },
        )
        assertTrue(cancelled.cancelled)
        assertFalse(cancelled.failed)
        assertTrue(failed.failed)
        assertFalse(failed.cancelled)
    }

    @Test
    fun `a candidate collects all tests only when every source says so`() = withDirectory { root ->
        val cases = mapOf(
            "task collects a selection" to edit("task.manifest", "all=true", "all=false"),
            "root expectation unsupported" to edit("expected.manifest", "supported=true", "supported=false"),
            "worker incomplete" to remove(completedPath("worker-2")),
            "worker unsupported" to edit(completedPath("worker-2"), "supported=true", "supported=false"),
        )
        cases.entries.forEachIndexed { index, (name, change) ->
            val task = task(Files.createDirectory(root.resolve("case-$index")))
            worker(task, "worker-1", "AlphaTest", dependency("Alpha", "alpha-1"))
            worker(task, "worker-2", "BetaTest", dependency("Beta", "beta-1"))
            change(task)

            val candidate = assertNotNull(CollectorMapReader.read(task, "collector-1", "run-1"), name)

            assertFalse(candidate.collectsAllTests, name)
        }
    }

    @Test
    fun `worker expectations that disagree on support stop the candidate from collecting all tests`() =
        withDirectory { root ->
            val task = mavenTask(root)
            val expected = workerDirectory(task, "fork-2").resolve("expected.manifest")
            Files.writeString(expected, Files.readString(expected).replace("supported=true", "supported=false"))

            assertFalse(assertNotNull(CollectorMapReader.read(task, "collector-1", "run-1")).collectsAllTests)
        }

    @Test
    fun `worker expectations that agree let the candidate collect all tests`() = withDirectory { root ->
        val candidate = assertNotNull(CollectorMapReader.read(mavenTask(root), "collector-1", "run-1"))

        assertTrue(candidate.collectsAllTests)
        assertEquals(setOf(testClass("AlphaTest"), testClass("BetaTest")), candidate.expectedTestClasses)
    }

    @Test
    fun `an unsupported expectation without tests is accepted but never collects all tests`() = withDirectory { root ->
        val task = task(root)
        worker(task, "worker-1", "AlphaTest", dependency("Alpha", "alpha-1"))
        Files.writeString(task.resolve("expected.manifest"), "format=1\nsupported=false\n")

        val candidate = assertNotNull(CollectorMapReader.read(task, "collector-1", "run-1"))

        assertEquals(emptySet(), candidate.expectedTestClasses)
        assertFalse(candidate.collectsAllTests)
    }

    @Test
    fun `an unsupported worker without tests is accepted but never collects all tests`() = withDirectory { root ->
        val task = task(root)
        worker(task, "worker-1", "AlphaTest", dependency("Alpha", "alpha-1"))
        Files.delete(task.resolve(mapPath("worker-1", "AlphaTest")))
        Files.writeString(
            task.resolve(completedPath("worker-1")),
            "format=1\nworker=${encode("worker-1")}\nsupported=false\n",
        )

        val candidate = assertNotNull(CollectorMapReader.read(task, "collector-1", "run-1"))

        assertEquals(listOf(WorkerDependencyMap("worker-1", emptyList())), candidate.workers)
        assertFalse(candidate.collectsAllTests)
    }

    @Test
    fun `blank collector or run identity cannot form a candidate`() = withDirectory { root ->
        val task = task(root)
        worker(task, "worker-1", "AlphaTest", dependency("Alpha", "alpha-1"))

        assertNull(CollectorMapReader.read(task, "", "run-1"))
        assertNull(CollectorMapReader.read(task, " ", "run-1"))
        assertNull(CollectorMapReader.read(task, "collector-1", ""))
        assertNull(CollectorMapReader.read(task, "collector-1", " "))
        assertNotNull(CollectorMapReader.read(task, "collector-1", "run-1"))
    }

    @Test
    fun `a task output with no expectation or a root expectation beside workers is invalid`() = withDirectory { root ->
        val none = task(Files.createDirectory(root.resolve("none")))
        Files.delete(none.resolve("expected.manifest"))
        worker(none, "worker-1", "AlphaTest", dependency("Alpha", "alpha-1"))
        worker(none, "worker-2", "BetaTest", dependency("Beta", "beta-1"))
        val both = mavenTask(Files.createDirectory(root.resolve("both")))
        Files.writeString(
            both.resolve("expected.manifest"),
            "format=1\nsupported=true\ntest=${encode("AlphaTest")}\ntest=${encode("BetaTest")}\n",
        )

        assertNull(CollectorMapReader.read(none, "collector-1", "run-1"))
        assertNull(CollectorMapReader.read(both, "collector-1", "run-1"))
    }

    private fun mavenTask(root: Path): Path {
        val task = task(root)
        Files.delete(task.resolve("expected.manifest"))
        worker(task, "fork-1", "AlphaTest", dependency("Alpha", "alpha-1"), expected = "AlphaTest")
        worker(task, "fork-2", "BetaTest", dependency("Beta", "beta-1"), expected = "BetaTest")
        return task
    }
}
