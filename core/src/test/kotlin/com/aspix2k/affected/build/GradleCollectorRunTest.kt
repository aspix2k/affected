package com.aspix2k.affected.build

import com.aspix2k.affected.impact.DependencyMapStore
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GradleCollectorRunTest {

    @Test
    fun `a successful complete run atomically promotes and removes its output`() = withDirectory { root ->
        val artifacts = artifacts(root)
        val cache = root.resolve("cache")
        val run = assertNotNull(GradleCollectorRun.create(cache, artifacts))
        val taskKey = "file:///fixture/|:app:test"
        task(run.outputRoot, taskKey, "AlphaTest", "alpha-1")

        run.complete(passed = true)

        val map = assertNotNull(DependencyMapStore(cache.resolve("maps")).read(taskKey))
        assertEquals(setOf("AlphaTest"), map.records.mapTo(HashSet()) { it.testClass.value })
        assertEquals(sha256("alpha-1"), map.artifacts.single().sha256)
        assertFalse(Files.exists(run.outputRoot))
    }

    @Test
    fun `a failed run leaves the previous complete map unchanged`() = withDirectory { root ->
        val artifacts = artifacts(root)
        val cache = root.resolve("cache")
        val taskKey = "file:///fixture/|:app:test"
        val first = assertNotNull(GradleCollectorRun.create(cache, artifacts))
        task(first.outputRoot, taskKey, "AlphaTest", "alpha-1")
        first.complete(passed = true)
        val previous = assertNotNull(DependencyMapStore(cache.resolve("maps")).read(taskKey))
        val failed = assertNotNull(GradleCollectorRun.create(cache, artifacts))
        task(failed.outputRoot, taskKey, "AlphaTest", "alpha-2")

        failed.complete(passed = false)

        assertEquals(previous, DependencyMapStore(cache.resolve("maps")).read(taskKey))
        assertFalse(Files.exists(failed.outputRoot))
    }

    @Test
    fun `a cancelled successful process cannot replace the previous map`() = withDirectory { root ->
        val artifacts = artifacts(root)
        val cache = root.resolve("cache")
        val taskKey = "file:///fixture/|:app:test"
        val first = assertNotNull(GradleCollectorRun.create(cache, artifacts))
        task(first.outputRoot, taskKey, "AlphaTest", "alpha-1")
        first.complete(passed = true)
        val previous = assertNotNull(DependencyMapStore(cache.resolve("maps")).read(taskKey))
        val cancelled = assertNotNull(GradleCollectorRun.create(cache, artifacts))
        task(cancelled.outputRoot, taskKey, "AlphaTest", "alpha-2")

        cancelled.cancel()
        cancelled.complete(passed = true)

        assertEquals(previous, DependencyMapStore(cache.resolve("maps")).read(taskKey))
        assertFalse(Files.exists(cancelled.outputRoot))
    }

    @Test
    fun `collector arguments preserve individual paths`() = withDirectory { root ->
        val artifacts = artifacts(Files.createDirectory(root.resolve("artifacts with spaces")))
        val run = assertNotNull(GradleCollectorRun.create(root.resolve("cache with spaces"), artifacts))

        assertEquals(
            listOf(
                "--init-script",
                artifacts.initScript.toAbsolutePath().normalize().toString(),
                "-Daffected.collector.agent=${artifacts.agent.toAbsolutePath().normalize()}",
                "-Daffected.collector.listener=${artifacts.listener.toAbsolutePath().normalize()}",
                "-Daffected.collector.output=${run.outputRoot}",
            ),
            run.arguments,
        )
        run.complete(passed = false)
    }

    @Test
    fun `collector artifacts are resolved from the installed plugin root`() = withDirectory { root ->
        val plugin = root.resolve("affected")
        val artifacts = installedArtifacts(plugin)
        val classPath = Files.writeString(plugin.resolve("lib/modules/affected.core.jar"), "core")

        assertEquals(artifacts, findGradleCollectorArtifacts(classPath))
    }

    @Test
    fun `collector artifacts are not resolved outside the bounded plugin layout`() = withDirectory { root ->
        installedArtifacts(root)
        val classDirectory = Files.createDirectories(root.resolve("one/two/three/four/five/classes"))

        assertNull(findGradleCollectorArtifacts(classDirectory))
    }

    private fun artifacts(root: Path): GradleCollectorArtifacts {
        Files.createDirectories(root)
        val agent = Files.writeString(root.resolve("agent.jar"), "agent")
        val listener = Files.writeString(root.resolve("listener.jar"), "listener")
        val script = Files.writeString(root.resolve("collector.gradle"), "script")
        return GradleCollectorArtifacts(agent, listener, script)
    }

    private fun installedArtifacts(plugin: Path): GradleCollectorArtifacts {
        val agentDirectory = Files.createDirectories(plugin.resolve("agent"))
        Files.createDirectories(plugin.resolve("lib/modules"))
        return GradleCollectorArtifacts(
            Files.writeString(agentDirectory.resolve("affected-collector-agent.jar"), "agent"),
            Files.writeString(agentDirectory.resolve("affected-collector-listener.jar"), "listener"),
            Files.writeString(agentDirectory.resolve("affected-collector.init.gradle"), "script"),
        )
    }

    private fun task(root: Path, taskKey: String, test: String, hashSeed: String) {
        val task = Files.createDirectory(root.resolve("task-${sha256(taskKey)}"))
        Files.writeString(
            task.resolve("task.manifest"),
            "format=1\ntask=${encode(taskKey)}\nruntime=${encode("runtime-1")}\ninput=${encode("input-1")}\nall=true\n",
        )
        Files.writeString(
            task.resolve("expected.manifest"),
            "format=1\nsupported=true\ntest=${encode(test)}\n",
        )
        val worker = "worker-1"
        val directory = Files.createDirectory(task.resolve("worker-${sha256(worker)}"))
        Files.writeString(directory.resolve("started.manifest"), "format=1\nworker=${encode(worker)}\n")
        Files.writeString(
            directory.resolve("complete.manifest"),
            "format=1\nworker=${encode(worker)}\nsupported=true\ntest=${encode(test)}\n",
        )
        Files.writeString(
            directory.resolve("test-${sha256(test)}.map"),
            "format=1\ntest=${encode(test)}\n" +
                "dependency=${encode("Alpha")}|${encode("file:///classes/")}|${sha256(hashSeed)}\n",
        )
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun withDirectory(block: (Path) -> Unit) {
        val directory = createTempDirectory("affected-gradle-run-test-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
