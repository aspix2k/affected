package com.aspix2k.affected.build.maven

import com.aspix2k.affected.build.BuildChanges
import java.io.File

internal const val MAVEN_SYSTEM_ID = "MAVEN"
internal const val MAVEN_COMPILE_GOAL = "test-compile"

internal fun mavenRequiresWorkspace(root: String, changes: BuildChanges): Boolean {
    val rootPath = File(root).toPath().toAbsolutePath().normalize()
    return changes.files.any { raw ->
        val file = File(raw).toPath().toAbsolutePath().normalize()
        file.startsWith(rootPath) && mavenBuildWideChange(rootPath.relativize(file).toString().replace('\\', '/'))
    }
}

internal fun mavenBuildWideChange(relative: String): Boolean =
    relative.substringAfterLast('/') == "pom.xml" || relative.startsWith(".mvn/")
