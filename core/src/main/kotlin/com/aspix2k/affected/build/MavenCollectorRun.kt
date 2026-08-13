package com.aspix2k.affected.build

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal data class MavenCollectorArtifacts(
    val agent: Path,
    val extension: Path,
)

internal class MavenCollectorRun private constructor(
    private val run: CollectorRun,
    artifacts: MavenCollectorArtifacts,
) {
    val outputRoot: Path = run.outputRoot

    val arguments: List<String> = listOf(
        "-Dmaven.ext.class.path=${artifacts.extension}",
        "-Daffected.collector.mavenAgent=${artifacts.agent}",
        "-Daffected.collector.output=$outputRoot",
        "-Daffected.collector.maps=${run.mapsRoot}",
        "-Daffected.collector.version=${run.collectorVersion}",
    )

    fun cancel() = run.cancel()

    fun complete(passed: Boolean) = run.complete(passed)

    internal companion object {
        fun create(cacheRoot: Path, requestedArtifacts: MavenCollectorArtifacts): MavenCollectorRun? {
            val run = CollectorRun.create(cacheRoot, listOf(requestedArtifacts.agent, requestedArtifacts.extension))
                ?: return null
            return MavenCollectorRun(run, MavenCollectorArtifacts(run.artifacts[0], run.artifacts[1]))
        }
    }
}

internal fun findMavenCollectorArtifacts(classPath: Path): MavenCollectorArtifacts? {
    var directory = classPath.toAbsolutePath().normalize().let {
        if (Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)) it else it.parent
    } ?: return null
    repeat(MAVEN_MAX_PLUGIN_PARENT_DEPTH) {
        val artifacts = MavenCollectorArtifacts(
            directory.resolve(MAVEN_AGENT_PATH),
            directory.resolve(MAVEN_EXTENSION_PATH),
        )
        if (listOf(artifacts.agent, artifacts.extension).all {
                Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(it)
            }
        ) {
            return artifacts
        }
        directory = directory.parent ?: return null
    }
    return null
}

private const val MAVEN_MAX_PLUGIN_PARENT_DEPTH = 5
private const val MAVEN_AGENT_PATH = "agent/affected-maven-agent.jar"
private const val MAVEN_EXTENSION_PATH = "agent/affected-maven-extension.jar"
