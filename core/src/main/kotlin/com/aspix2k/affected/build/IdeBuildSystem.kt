package com.aspix2k.affected.build

import com.intellij.openapi.project.Project

open class IdeBuildSystem internal constructor(
    internal val engine: EngineBuildSystem,
) : ChangeAwareSuspendingBuildSystem {

    override val id: String get() = engine.id

    override val sourceExtensions: Set<String> get() = engine.sourceExtensions

    override fun isPresent(project: Project): Boolean = engine.isPresent(IdeWorkspace(project))

    override fun modules(project: Project): List<BuildModule> = engine.modules(IdeWorkspace(project))

    override fun run(project: Project, root: String, tasks: List<String>) =
        engine.run(IdeWorkspace(project), root, tasks)

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        engine.runAndWait(IdeWorkspace(project), root, tasks)

    override suspend fun runAndWaitSuspending(
        project: Project,
        root: String,
        tasks: List<String>,
        changes: BuildChanges,
    ): Boolean {
        val workspace = IdeWorkspace(project)
        return (engine as? ChangeAwareEngineBuildSystem)?.runAndWait(workspace, root, tasks, changes)
            ?: engine.runAndWait(workspace, root, tasks)
    }

    override fun isTestSource(path: String): Boolean = engine.isTestSource(path)

    override val consumersNeedSignatureChange: Boolean get() = engine.consumersNeedSignatureChange

    override val singleOwnerPerRoot: Boolean get() = engine.singleOwnerPerRoot
}
