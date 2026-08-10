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
        dependency: ClassDependency,
        supported: Boolean = true,
    ) {
        val directory = started(task, worker)
        Files.writeString(
            directory.resolve("complete.manifest"),
            "format=1\nworker=${encode(worker)}\nsupported=$supported\ntest=${encode(test)}\n",
        )
        Files.writeString(
            directory.resolve("test-${sha256(test)}.map"),
            "format=1\ntest=${encode(test)}\n${dependencyLine(dependency)}",
        )
    }

    private fun workerDirectory(task: Path, worker: String): Path = task.resolve("worker-${sha256(worker)}")

    private fun dependencyLine(dependency: ClassDependency): String =
        "dependency=${encode(dependency.id.className)}|${encode(dependency.id.codeSource)}|${dependency.sha256}\n"

    private fun dependency(name: String, hashSeed: String) = ClassDependency(
        DependencyId(name, "file:///classes/"),
        sha256(hashSeed),
    )

    private fun testClass(name: String) = TestClassId(name)

    private fun record(test: TestClassId, vararg dependencies: ClassDependency) =
        TestDependencyRecord(test, dependencies.toSet())

    private fun complete(artifacts: List<ClassDependency>, records: List<TestDependencyRecord>) =
        CompleteDependencyMap(
            identity = DependencyMapIdentity(1, "collector-1", "root|:app|testDebugUnitTest", "runtime-1", "input-1"),
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
