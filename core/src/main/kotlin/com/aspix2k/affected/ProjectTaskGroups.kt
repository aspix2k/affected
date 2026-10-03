package com.aspix2k.affected

import com.aspix2k.affected.build.process.CommandRunner
import com.intellij.openapi.project.Project
import java.nio.file.Path

suspend fun TaskGroup.runInPlannedExecutionRoot(project: Project, block: suspend () -> Boolean): Boolean {
    val projectRoot = project.basePath?.let(Path::of) ?: return false
    return runInPlannedExecutionRoot(
        projectRoot,
        onInvalid = { CommandRunner.refuseInvalidExecutionRoot(project, root, "Affected") },
        block,
    )
}
