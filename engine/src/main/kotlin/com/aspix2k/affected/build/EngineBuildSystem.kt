package com.aspix2k.affected.build

internal interface EngineBuildSystem {

    val id: String

    val sourceExtensions: Set<String>

    fun isPresent(workspace: Workspace): Boolean

    fun modules(workspace: Workspace): List<BuildModule>

    fun run(workspace: Workspace, root: String, tasks: List<String>)

    suspend fun runAndWait(workspace: Workspace, root: String, tasks: List<String>): Boolean

    fun isTestSource(path: String): Boolean = false

    val consumersNeedSignatureChange: Boolean get() = false

    val singleOwnerPerRoot: Boolean get() = false
}

internal interface ChangeAwareEngineBuildSystem : EngineBuildSystem {
    suspend fun runAndWait(workspace: Workspace, root: String, tasks: List<String>, changes: BuildChanges): Boolean
}
