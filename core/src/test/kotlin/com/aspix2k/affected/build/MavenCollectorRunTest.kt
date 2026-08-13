package com.aspix2k.affected.build

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MavenCollectorRunTest {

    @Test
    fun `collector arguments preserve paths and use one shared run`() = withDirectory { root ->
        val artifacts = artifacts(Files.createDirectory(root.resolve("artifacts with spaces")))
        val cache = root.resolve("cache with spaces")
        val run = assertNotNull(MavenCollectorRun.create(cache, artifacts))
        val canonicalArtifacts = MavenCollectorArtifacts(
            artifacts.agent.toRealPath(LinkOption.NOFOLLOW_LINKS),
            artifacts.extension.toRealPath(LinkOption.NOFOLLOW_LINKS),
        )

        assertEquals(
            listOf(
                "-Dmaven.ext.class.path=${canonicalArtifacts.extension}",
                "-Daffected.collector.mavenAgent=${canonicalArtifacts.agent}",
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

        assertEquals(artifacts, findMavenCollectorArtifacts(classPath))
    }

    @Test
    fun `collector artifacts are not resolved outside the bounded plugin layout`() = withDirectory { root ->
        installedArtifacts(root)
        val classDirectory = Files.createDirectories(root.resolve("one/two/three/four/five/classes"))

        assertNull(findMavenCollectorArtifacts(classDirectory))
    }

    private fun artifacts(root: Path): MavenCollectorArtifacts {
        Files.createDirectories(root)
        return MavenCollectorArtifacts(
            Files.writeString(root.resolve("maven-agent.jar"), "agent"),
            Files.writeString(root.resolve("maven-extension.jar"), "extension"),
        )
    }

    private fun installedArtifacts(plugin: Path): MavenCollectorArtifacts {
        val agentDirectory = Files.createDirectories(plugin.resolve("agent"))
        Files.createDirectories(plugin.resolve("lib/modules"))
        return MavenCollectorArtifacts(
            Files.writeString(agentDirectory.resolve("affected-maven-agent.jar"), "agent"),
            Files.writeString(agentDirectory.resolve("affected-maven-extension.jar"), "extension"),
        )
    }

    private fun artifactVersion(artifacts: MavenCollectorArtifacts): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(artifacts.agent, artifacts.extension)
            .forEach { path -> digest.update(Files.readAllBytes(path)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun withDirectory(block: (Path) -> Unit) {
        val directory = createTempDirectory("affected-maven-run-test-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
