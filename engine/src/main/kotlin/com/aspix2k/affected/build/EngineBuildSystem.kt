package com.aspix2k.affected.build

internal interface EngineBuildSystem : BuildSystemTraits {

    fun isPresent(workspace: Workspace): Boolean

    fun modules(workspace: Workspace): List<BuildModule>

    fun run(workspace: Workspace, root: String, tasks: List<String>)

    suspend fun runAndWait(workspace: Workspace, root: String, tasks: List<String>): Boolean
}

internal interface ChangeAwareEngineBuildSystem : EngineBuildSystem {
    suspend fun runAndWait(workspace: Workspace, root: String, tasks: List<String>, changes: BuildChanges): Boolean
}
