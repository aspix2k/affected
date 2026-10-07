package com.aspix2k.affected.build

import java.io.File
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

internal val testSnapshotRoot: Path by lazy { createTempDirectory("affected-snapshots") }

internal fun testWorkspace(root: File, cache: Path = testSnapshotRoot): Workspace =
    object : Workspace by IdeWorkspace(multiRootProject(root)) {
        override val cacheDirectory: Path = cache
    }

internal val noRootWorkspace: Workspace by lazy { testWorkspace(File(".")) }
