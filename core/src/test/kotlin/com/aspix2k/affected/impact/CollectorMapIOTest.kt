package com.aspix2k.affected.impact

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class CollectorMapIOTest {

    @Test
    fun `a complete multi-worker output becomes a promotable candidate`() = withDirectory { root ->
        val task = task(root)
        worker(task, "worker-1", "AlphaTest", dependency("Alpha", "alpha-1"))
        worker(task, "worker-2", "BetaTest", dependency("Beta", "beta-1"))

        val candidate = assertNotNull(CollectorMapReader.read(task, "collector-1", "run-1"))
        val promoted = assertNotNull(DependencyMapPromotion.promote(null, candidate))

        assertEquals(setOf("worker-1", "worker-2"), candidate.expectedWorkers)
        assertEquals(setOf(testClass("AlphaTest"), testClass("BetaTest")), candidate.expectedTestClasses)
        assertEquals(2, promoted.artifacts.size)
        assertEquals(2, promoted.records.size)
    }

    @Test
    fun `worker scoped expectations promote the complete Maven union`() = withDirectory { root ->
        val task = task(root)
        Files.delete(task.resolve("expected.manifest"))
        worker(task, "fork-1", "AlphaTest", dependency("Alpha", "alpha-1"), expected = "AlphaTest")
        worker(task, "fork-2", "BetaTest", dependency("Beta", "beta-1"), expected = "BetaTest")

        val candidate = assertNotNull(CollectorMapReader.read(task, "collector-1", "maven-run"))
        val promoted = assertNotNull(DependencyMapPromotion.promote(null, candidate))

        assertEquals(setOf("fork-1", "fork-2"), candidate.expectedWorkers)
        assertEquals(setOf(testClass("AlphaTest"), testClass("BetaTest")), candidate.expectedTestClasses)
        assertEquals(2, promoted.records.size)
    }

    @Test
    fun `a Maven worker without its expected set invalidates the candidate`() = withDirectory { root ->
        val task = task(root)
        Files.delete(task.resolve("expected.manifest"))
        worker(task, "fork-1", "AlphaTest", dependency("Alpha", "alpha-1"), expected = "AlphaTest")
        worker(task, "fork-2", "BetaTest", dependency("Beta", "beta-1"))

        assertNull(CollectorMapReader.read(task, "collector-1", "missing-expected"))
    }

    @Test
    fun `mixed root and worker expectations invalidate the candidate`() = withDirectory { root ->
        val task = task(root)
        worker(task, "fork-1", "AlphaTest", dependency("Alpha", "alpha-1"), expected = "AlphaTest")
        worker(task, "fork-2", "BetaTest", dependency("Beta", "beta-1"))

        assertNull(CollectorMapReader.read(task, "collector-1", "mixed-expected"))
    }

    @Test
    fun `an incomplete Maven worker keeps the previous map`() = withDirectory { root ->
        val task = task(root)
        Files.delete(task.resolve("expected.manifest"))
        worker(task, "fork-1", "AlphaTest", dependency("Alpha", "alpha-1"), expected = "AlphaTest")
        val partial = started(task, "fork-2")
        Files.writeString(
            partial.resolve("expected.manifest"),
            "format=1\nsupported=true\ntest=${encode("BetaTest")}\n",
        )
        val previous = complete(
            artifacts = listOf(dependency("Previous", "previous-1")),
            records = listOf(record(testClass("PreviousTest"), dependency("Previous", "previous-1"))),
        )

        val candidate = assertNotNull(CollectorMapReader.read(task, "collector-1", "partial-maven"))

        assertSame(previous, DependencyMapPromotion.promote(previous, candidate))
    }

    @Test
    fun `duplicate Maven test ownership keeps the previous map`() = withDirectory { root ->
        val task = task(root)
        Files.delete(task.resolve("expected.manifest"))
        worker(task, "fork-1", "AlphaTest", dependency("Alpha", "alpha-1"), expected = "AlphaTest")
        worker(task, "fork-2", "AlphaTest", dependency("Alpha", "alpha-1"), expected = "AlphaTest")
        val previous = complete(
            artifacts = listOf(dependency("Previous", "previous-1")),
            records = listOf(record(testClass("PreviousTest"), dependency("Previous", "previous-1"))),
        )

        val candidate = assertNotNull(CollectorMapReader.read(task, "collector-1", "duplicate-maven"))

        assertSame(previous, DependencyMapPromotion.promote(previous, candidate))
    }

    @Test
    fun `a Maven expected class missing from completed workers keeps the previous map`() = withDirectory { root ->
        val task = task(root)
        Files.delete(task.resolve("expected.manifest"))
        worker(task, "fork-1", "AlphaTest", dependency("Alpha", "alpha-1"), expected = "AlphaTest")
        worker(task, "fork-2", "BetaTest", dependency("Beta", "beta-1"), expected = "MissingTest")
        val previous = complete(
            artifacts = listOf(dependency("Previous", "previous-1")),
            records = listOf(record(testClass("PreviousTest"), dependency("Previous", "previous-1"))),
        )

        val candidate = assertNotNull(CollectorMapReader.read(task, "collector-1", "missing-worker"))

        assertSame(previous, DependencyMapPromotion.promote(previous, candidate))
    }

    @Test
    fun `a test without production dependencies remains in the complete map`() = withDirectory { root ->
        val task = task(root)
        worker(task, "worker-1", "AlphaTest")
        worker(task, "worker-2", "BetaTest", dependency("Beta", "beta-1"))

        val candidate = assertNotNull(CollectorMapReader.read(task, "collector-1", "run-empty"))
        val promoted = assertNotNull(DependencyMapPromotion.promote(null, candidate))

        assertEquals(emptySet(), promoted.records.single { it.testClass == testClass("AlphaTest") }.dependencies)
    }

    @Test
    fun `a started worker without completion keeps the previous map`() = withDirectory { root ->
        val task = task(root)
        worker(task, "worker-1", "AlphaTest", dependency("Alpha", "alpha-1"))
        started(task, "worker-2")
        val previous = complete(
            artifacts = listOf(dependency("Previous", "previous-1")),
            records = listOf(record(testClass("PreviousTest"), dependency("Previous", "previous-1"))),
        )

        val candidate = assertNotNull(CollectorMapReader.read(task, "collector-1", "run-2"))

        assertSame(previous, DependencyMapPromotion.promote(previous, candidate))
    }

    @Test
    fun `unsupported worker output cannot replace a complete map`() = withDirectory { root ->
        val task = task(root)
        worker(task, "worker-1", "AlphaTest", dependency("Alpha", "alpha-1"), supported = false)
        val previous = complete(
            artifacts = listOf(dependency("Previous", "previous-1")),
            records = listOf(record(testClass("PreviousTest"), dependency("Previous", "previous-1"))),
        )

        val candidate = assertNotNull(CollectorMapReader.read(task, "collector-1", "run-3"))

        assertSame(previous, DependencyMapPromotion.promote(previous, candidate))
    }

    @Test
    fun `a duplicate dependency line invalidates task output`() = withDirectory { root ->
        val task = task(root)
        val dependency = dependency("Alpha", "alpha-1")
        worker(task, "worker-1", "AlphaTest", dependency)
        val map = workerDirectory(task, "worker-1").resolve("test-${sha256("AlphaTest")}.map")
        Files.writeString(map, Files.readString(map) + dependencyLine(dependency))

        assertNull(CollectorMapReader.read(task, "collector-1", "run-4"))
    }

    @Test
    fun `a noncanonical base64 value invalidates task output`() = withDirectory { root ->
        val task = task(root)
        worker(task, "worker-1", "AlphaTest", dependency("Alpha", "alpha-1"))
        val manifest = workerDirectory(task, "worker-1").resolve("complete.manifest")
        Files.writeString(manifest, Files.readString(manifest).replace("worker=d29ya2VyLTE", "worker=d29ya2VyLTE="))

        assertNull(CollectorMapReader.read(task, "collector-1", "run-5"))
    }

    @Test
    fun `a worker directory symlink invalidates task output`() = withDirectory { root ->
        val task = task(root)
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.createSymbolicLink(task.resolve("worker-${sha256("worker-1")}"), outside)

        assertNull(CollectorMapReader.read(task, "collector-1", "run-6"))
    }

    @Test
    fun `a missing class catalog invalidates task output`() = withDirectory { root ->
        val task = task(root)
        Files.delete(task.resolve("catalog.manifest"))
        worker(task, "worker-1", "AlphaTest", dependency("Alpha", "alpha-1"))

        assertNull(CollectorMapReader.read(task, "collector-1", "run-7"))
    }

    @Test
    fun `complete maps round trip through the atomic store`() = withDirectory { root ->
        val store = DependencyMapStore(root.resolve("store"))
        val dependency = dependency("Alpha", "alpha-1")
        val expected = complete(listOf(dependency), listOf(record(testClass("AlphaTest"), dependency)))

        store.write(expected)

        assertEquals(expected, store.read(expected.identity.taskKey))
    }

    @Test
    fun `a corrupt stored map is ignored`() = withDirectory { root ->
        val storeRoot = root.resolve("store")
        val store = DependencyMapStore(storeRoot)
        val dependency = dependency("Alpha", "alpha-1")
        val map = complete(listOf(dependency), listOf(record(testClass("AlphaTest"), dependency)))
        store.write(map)
        Files.writeString(storeRoot.resolve("map-${sha256(map.identity.taskKey)}.map"), "format=1\n")

        assertNull(store.read(map.identity.taskKey))
    }

    @Test
    fun `a stored map missing one valid record is ignored`() = withDirectory { root ->
        val storeRoot = root.resolve("store")
        val store = DependencyMapStore(storeRoot)
        val alpha = dependency("Alpha", "alpha-1")
        val beta = dependency("Beta", "beta-1")
        val map = complete(
            listOf(alpha, beta),
            listOf(record(testClass("AlphaTest"), alpha), record(testClass("BetaTest"), beta)),
        )
        store.write(map)
        val path = storeRoot.resolve("map-${sha256(map.identity.taskKey)}.map")
        Files.writeString(path, Files.readAllLines(path).dropLast(1).joinToString("\n", postfix = "\n"))

        assertNull(store.read(map.identity.taskKey))
    }

    private fun task(root: Path): Path {
        val key = "root|:app|testDebugUnitTest"
        return Files.createDirectory(root.resolve("task-${sha256(key)}")).also { directory ->
            Files.writeString(
                directory.resolve("task.manifest"),
                "format=1\ntask=${encode(key)}\nruntime=${encode("runtime-1")}\ninput=${encode("input-1")}\nall=true\n",
            )
            Files.writeString(
                directory.resolve("expected.manifest"),
                "format=1\nsupported=true\ntest=${encode("AlphaTest")}\ntest=${encode("BetaTest")}\n",
            )
            Files.writeString(
                directory.resolve("catalog.manifest"),
                "format=1\n${artifactLine(dependency("Alpha", "alpha-1"))}" +
                    artifactLine(dependency("Beta", "beta-1")),
            )
        }
    }

    private fun started(task: Path, worker: String): Path = workerDirectory(task, worker).also { directory ->
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("started.manifest"), "format=1\nworker=${encode(worker)}\n")
    }

    private fun worker(
        task: Path,
        worker: String,
        test: String,
        vararg dependencies: ClassDependency,
        supported: Boolean = true,
        expected: String? = null,
    ) {
        val directory = started(task, worker)
        if (expected != null) {
            Files.writeString(
                directory.resolve("expected.manifest"),
                "format=1\nsupported=$supported\ntest=${encode(expected)}\n",
            )
        }
        Files.writeString(
            directory.resolve("complete.manifest"),
            "format=1\nworker=${encode(worker)}\nsupported=$supported\ntest=${encode(test)}\n",
        )
        Files.writeString(
            directory.resolve("test-${sha256(test)}.map"),
            "format=1\ntest=${encode(test)}\n${dependencies.joinToString("") { dependencyLine(it) }}",
        )
    }

    private fun workerDirectory(task: Path, worker: String): Path = task.resolve("worker-${sha256(worker)}")

    private fun dependencyLine(dependency: ClassDependency): String =
        "dependency=${encode(dependency.id.className)}|${encode(dependency.id.codeSource)}|${dependency.sha256}\n"

    private fun artifactLine(dependency: ClassDependency): String =
        "artifact=${encode(dependency.id.className)}|${encode(dependency.id.codeSource)}|${dependency.sha256}\n"

    private fun dependency(name: String, hashSeed: String) = ClassDependency(
        DependencyId(name, "file:///classes/"),
        sha256(hashSeed),
    )

    private fun testClass(name: String) = TestClassId(name)

    private fun record(test: TestClassId, vararg dependencies: ClassDependency) =
        TestDependencyRecord(test, dependencies.toSet())

    private fun complete(artifacts: List<ClassDependency>, records: List<TestDependencyRecord>) =
        CompleteDependencyMap(
            identity = DependencyMapIdentity(
                DEPENDENCY_MAP_SCHEMA_VERSION,
                "collector-1",
                "root|:app|testDebugUnitTest",
                "runtime-1",
                "input-1",
            ),
            artifacts = artifacts,
            records = records,
            completedRunId = "run-1",
        )

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun withDirectory(block: (Path) -> Unit) {
        val directory = createTempDirectory("affected-map-test-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
