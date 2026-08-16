package com.aspix2k.affected.build

import com.aspix2k.affected.impact.DependencyMapStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class GradleCollectorRunTest {

    @Test
    fun `a successful complete run atomically promotes and removes its output`() = withDirectory { root ->
        val artifacts = artifacts(root)
        val cache = root.resolve("cache")
        val run = assertNotNull(GradleCollectorRun.create(cache, artifacts))
        val taskKey = "file:///fixture/|:app:test"
        writeCollectorTask(run.outputRoot, taskKey, "AlphaTest", "alpha-1")

        run.complete(passed = true)

        val map = assertNotNull(DependencyMapStore(cache.resolve("maps")).read(taskKey))
        assertEquals(setOf("AlphaTest"), map.records.mapTo(HashSet()) { it.testClass.value })
        assertEquals(collectorSha256("alpha-1"), map.artifacts.single().sha256)
        assertFalse(Files.exists(run.outputRoot))
    }

    @Test
    fun `a failed run leaves the previous complete map unchanged`() = withDirectory { root ->
        val artifacts = artifacts(root)
        val cache = root.resolve("cache")
        val taskKey = "file:///fixture/|:app:test"
        val first = assertNotNull(GradleCollectorRun.create(cache, artifacts))
        writeCollectorTask(first.outputRoot, taskKey, "AlphaTest", "alpha-1")
        first.complete(passed = true)
        val previous = assertNotNull(DependencyMapStore(cache.resolve("maps")).read(taskKey))
        val failed = assertNotNull(GradleCollectorRun.create(cache, artifacts))
        writeCollectorTask(failed.outputRoot, taskKey, "AlphaTest", "alpha-2")

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
        writeCollectorTask(first.outputRoot, taskKey, "AlphaTest", "alpha-1")
        first.complete(passed = true)
        val previous = assertNotNull(DependencyMapStore(cache.resolve("maps")).read(taskKey))
        val cancelled = assertNotNull(GradleCollectorRun.create(cache, artifacts))
        writeCollectorTask(cancelled.outputRoot, taskKey, "AlphaTest", "alpha-2")

        cancelled.cancel()
        cancelled.complete(passed = true)

        assertEquals(previous, DependencyMapStore(cache.resolve("maps")).read(taskKey))
        assertFalse(Files.exists(cancelled.outputRoot))
    }

    @Test
    fun `collector remains published when cancellation wins the dispatcher return`() = runBlocking {
        val root = createTempDirectory("affected-gradle-run-publish-test-")
        val published = AtomicReference<GradleCollectorRun?>()
        val created = CompletableDeferred<GradleCollectorRun>()
        val release = CompletableDeferred<Unit>()
        try {
            val task = async {
                publishGradleCollector(published) {
                    assertNotNull(GradleCollectorRun.create(root.resolve("cache"), artifacts(root))).also {
                        created.complete(it)
                        runBlocking { release.await() }
                    }
                }
            }
            val run = created.await()

            task.cancel()
            release.complete(Unit)

            assertFailsWith<CancellationException> { task.await() }
            assertSame(run, published.get())
            run.complete(passed = false)
            assertFalse(Files.exists(run.outputRoot))
        } finally {
            release.complete(Unit)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `collector arguments preserve individual paths`() = withDirectory { root ->
        val artifacts = artifacts(Files.createDirectory(root.resolve("artifacts with spaces")))
        val cache = root.resolve("cache with spaces")
        val run = assertNotNull(GradleCollectorRun.create(cache, artifacts))
        val canonicalArtifacts = GradleCollectorArtifacts(
            artifacts.agent.toRealPath(LinkOption.NOFOLLOW_LINKS),
            artifacts.listener.toRealPath(LinkOption.NOFOLLOW_LINKS),
            artifacts.initScript.toRealPath(LinkOption.NOFOLLOW_LINKS),
        )

        assertEquals(
            listOf(
                "--init-script",
                canonicalArtifacts.initScript.toString(),
                "-Daffected.collector.agent=${canonicalArtifacts.agent}",
                "-Daffected.collector.listener=${canonicalArtifacts.listener}",
                "-Daffected.collector.output=${run.outputRoot}",
                "-Daffected.collector.maps=${cache.toRealPath(LinkOption.NOFOLLOW_LINKS).resolve("maps")}",
                "-Daffected.collector.version=${artifactVersion(artifacts)}",
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

    @Test
    fun `failure strategy script is resolved only from the bounded installed layout`() = withDirectory { root ->
        val plugin = root.resolve("affected")
        val agent = Files.createDirectories(plugin.resolve("agent"))
        val modules = Files.createDirectories(plugin.resolve("lib/modules"))
        val script = Files.writeString(agent.resolve("affected-failure-strategy.init.gradle"), "strategy")
        val classPath = Files.writeString(modules.resolve("affected.core.jar"), "core")

        assertEquals(script, findGradleFailureStrategyScript(classPath))

        Files.delete(script)
        val outside = Files.writeString(root.resolve("outside.gradle"), "outside")
        if (runCatching { Files.createSymbolicLink(script, outside) }.isSuccess) {
            assertNull(findGradleFailureStrategyScript(classPath))
        }
        val deep = Files.createDirectories(root.resolve("one/two/three/four/five/classes"))
        assertNull(findGradleFailureStrategyScript(deep))
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

    private fun artifactVersion(artifacts: GradleCollectorArtifacts): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(artifacts.agent, artifacts.listener, artifacts.initScript)
            .forEach { path -> digest.update(Files.readAllBytes(path)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun withDirectory(block: (Path) -> Unit) {
        val directory = createTempDirectory("affected-gradle-run-test-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

internal fun writeCollectorTask(root: Path, taskKey: String, test: String, hashSeed: String) {
    val task = Files.createDirectory(root.resolve("task-${collectorSha256(taskKey)}"))
    Files.writeString(
        task.resolve("task.manifest"),
        "format=1\ntask=${collectorEncode(taskKey)}\nruntime=${collectorEncode("runtime-1")}" +
            "\ninput=${collectorEncode("input-1")}\nall=true\n",
    )
    Files.writeString(
        task.resolve("expected.manifest"),
        "format=1\nsupported=true\ntest=${collectorEncode(test)}\n",
    )
    Files.writeString(
        task.resolve("catalog.manifest"),
        "format=1\nartifact=${collectorEncode("Alpha")}|${collectorEncode("file:///classes/")}" +
            "|${collectorSha256(hashSeed)}\n",
    )
    val worker = "worker-1"
    val directory = Files.createDirectory(task.resolve("worker-${collectorSha256(worker)}"))
    Files.writeString(
        directory.resolve("started.manifest"),
        "format=1\nworker=${collectorEncode(worker)}\n",
    )
    Files.writeString(
        directory.resolve("complete.manifest"),
        "format=1\nworker=${collectorEncode(worker)}\nsupported=true\ntest=${collectorEncode(test)}\n",
    )
    Files.writeString(
        directory.resolve("test-${collectorSha256(test)}.map"),
        "format=1\ntest=${collectorEncode(test)}\n" +
            "dependency=${collectorEncode("Alpha")}|${collectorEncode("file:///classes/")}" +
            "|${collectorSha256(hashSeed)}\n",
    )
}

private fun collectorEncode(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

internal fun collectorSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
