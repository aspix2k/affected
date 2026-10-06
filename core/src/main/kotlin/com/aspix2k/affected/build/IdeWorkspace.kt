package com.aspix2k.affected.build

import com.aspix2k.affected.AffectedSettings
import com.aspix2k.affected.ProjectChanges
import com.aspix2k.affected.build.process.CommandRunner
import com.aspix2k.affected.build.process.ProcessHost
import com.aspix2k.affected.build.process.ideProcessHost
import com.aspix2k.affected.build.python.ideInterpreter
import com.aspix2k.affected.toBuildChanges
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Path

internal class IdeWorkspace(private val project: Project) : Workspace {

    override val root: File? get() = project.basePath?.let(::File)

    override val cacheDirectory: Path
        get() = PathManager.getSystemDir().resolve("affected").resolve(project.locationHash)

    override val stopAfterFirstFailure: Boolean get() = AffectedSettings.getInstance().stopAfterFirstFailure

    override val helperAssets: Path? get() = PathManager.getJarPathForClass(IdeWorkspace::class.java)?.let(Path::of)

    override val processHost: ProcessHost get() = ideProcessHost

    override val commandRuns: CommandRuns = IdeCommandRuns(project)

    override fun interpreterHint(directory: File): String? = ideInterpreter(project, directory)

    override fun changes(): BuildChanges = ProjectChanges.collect(project).toBuildChanges()
}

private class IdeCommandRuns(private val project: Project) : CommandRuns {

    override fun start(run: CommandRun) = CommandRunner.runBatch(
        project,
        run.workingDirectory,
        run.steps,
        run.title,
        run.unresolvedMessage,
        run.continueAfterFailure,
    )

    override suspend fun await(run: CommandRun): Boolean = CommandRunner.runBatchAndWait(
        project,
        run.workingDirectory,
        run.steps,
        run.title,
        run.unresolvedMessage,
        run.continueAfterFailure,
    )
}
