package com.aspix2k.affected.build

import java.nio.file.Path

internal data class GradleCollectorArtifacts(
    val agent: Path,
    val listener: Path,
    val initScript: Path,
)

internal class GradleCollectorRun private constructor(
    private val run: CollectorRun,
    artifacts: GradleCollectorArtifacts,
) {
    val outputRoot: Path = run.outputRoot

    val arguments: List<String> = listOf(
        "--init-script",
        artifacts.initScript.toString(),
        "-Daffected.collector.agent=${artifacts.agent}",
        "-Daffected.collector.listener=${artifacts.listener}",
        "-Daffected.collector.output=$outputRoot",
        "-Daffected.collector.maps=${run.mapsRoot}",
        "-Daffected.collector.version=${run.collectorVersion}",
    )

    fun cancel() = run.cancel()

    fun complete(passed: Boolean) = run.complete(passed)

    internal companion object {
        fun create(cacheRoot: Path, requestedArtifacts: GradleCollectorArtifacts): GradleCollectorRun? {
            val run = CollectorRun.create(
                cacheRoot,
                listOf(requestedArtifacts.agent, requestedArtifacts.listener, requestedArtifacts.initScript),
            ) ?: return null
            val artifacts = GradleCollectorArtifacts(run.artifacts[0], run.artifacts[1], run.artifacts[2])
            return GradleCollectorRun(run, artifacts)
        }
    }
}
