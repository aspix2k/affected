package com.aspix2k.affected.build

import com.aspix2k.affected.build.process.ProcessHost
import com.aspix2k.affected.build.process.SupervisorTestHost
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

internal class TestWorkspace(
    override val root: File? = null,
    override val cacheDirectory: Path = createTempDirectory("affected-cache"),
    override val stopAfterFirstFailure: Boolean = false,
    override val helperAssets: Path? = null,
) : Workspace {

    override val processHost: ProcessHost get() = SupervisorTestHost.host

    override val commandRuns: CommandRuns = object : CommandRuns {
        override fun start(run: CommandRun) = error("Unexpected command run: ${run.title}")

        override suspend fun await(run: CommandRun): Boolean = error("Unexpected command run: ${run.title}")
    }

    override fun interpreterHint(directory: File): String? = null

    override fun changes(): BuildChanges = error("Unexpected change collection")
}

internal val testSnapshotRoot: Path by lazy { createTempDirectory("affected-snapshots") }
