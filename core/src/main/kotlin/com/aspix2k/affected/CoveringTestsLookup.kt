package com.aspix2k.affected

import com.aspix2k.affected.build.MAPS_DIRECTORY
import com.aspix2k.affected.build.collectorVersion
import com.aspix2k.affected.build.gradle.findGradleCollectorArtifacts
import com.aspix2k.affected.build.maven.findMavenCollectorArtifacts
import com.aspix2k.affected.impact.CoveringTestsFinder
import com.aspix2k.affected.impact.CoveringTestsResult
import com.aspix2k.affected.impact.DependencyMapStore
import com.aspix2k.affected.impact.PromotedMaps
import com.aspix2k.affected.impact.SourceClassNames
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

object CoveringTestsLookup {

    fun find(project: Project, file: Path): CoveringTestsResult? = runCatching {
        require(Files.size(file) <= MAX_SOURCE_SIZE)
        val classNames = SourceClassNames.of(file.fileName.toString(), Files.readString(file)) ?: return null
        CoveringTestsFinder.find(sources(project), classNames, Files.getLastModifiedTime(file).toInstant())
    }.getOrNull()

    private fun sources(project: Project): List<PromotedMaps> {
        val cache = PathManager.getSystemDir().resolve(CACHE_DIRECTORY).resolve(project.locationHash)
        val plugin = PathManager.getJarPathForClass(CoveringTestsLookup::class.java)?.let(Path::of)
        return listOf(
            promoted(CoveringTestsFinder.GRADLE, cache) {
                plugin?.let(::findGradleCollectorArtifacts)?.let { listOf(it.agent, it.listener, it.initScript) }
            },
            promoted(CoveringTestsFinder.MAVEN, cache) {
                plugin?.let(::findMavenCollectorArtifacts)?.let { listOf(it.agent, it.extension) }
            },
        )
    }

    private fun promoted(system: String, cache: Path, artifacts: () -> List<Path>?) = PromotedMaps(
        system = system,
        stored = DependencyMapStore(cache.resolve(system).resolve(MAPS_DIRECTORY)).readAll(),
        currentCollectorVersion = runCatching { artifacts()?.let(::collectorVersion) }.getOrNull(),
    )

    private const val CACHE_DIRECTORY = "affected"
    private const val MAX_SOURCE_SIZE = 2L * 1024 * 1024
}
