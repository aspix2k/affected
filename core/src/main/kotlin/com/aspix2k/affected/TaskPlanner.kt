package com.aspix2k.affected

import com.aspix2k.affected.impact.TestSelection

data class ModuleInfo(
    val id: String,
    val systemId: String,
    val buildRoot: String,
    val testTask: String,
    val compileTask: String?,
    val hasTests: Boolean,
    val executionRoot: String = buildRoot,
    val executionId: String = id,
) {
    fun test(): String = "$executionId:$testTask"

    fun compile(): String? = compileTask?.let { "$executionId:$it" }

    internal fun plannedTest(): PlannedTask = PlannedTask(test(), TaskKind.TEST)

    internal fun plannedCompile(): PlannedTask? = compile()?.let { PlannedTask(it, TaskKind.COMPILE) }

    internal fun plannedNamed(task: String): PlannedTask = PlannedTask("$executionId:$task", TaskKind.NAMED)
}

data class TaskGroup(val systemId: String, val root: String, val tasks: List<String>)

internal enum class TaskKind {
    TEST,
    COMPILE,
    NAMED,
}

internal data class PlannedTask(
    val path: String,
    val kind: TaskKind,
    val selection: TestSelection = TestSelection.All,
) {
    init {
        require(path.isNotBlank())
        require(kind == TaskKind.TEST || selection == TestSelection.All)
    }
}

internal data class PlannedTaskGroup(
    val systemId: String,
    val root: String,
    val kind: TaskKind,
    val selection: TestSelection,
    val tasks: List<PlannedTask>,
) {
    init {
        require(tasks.isNotEmpty())
        require(tasks.all { it.kind == kind && it.selection == selection })
    }
}

data class Plan(val groups: List<TaskGroup>, val tested: Int, val compiled: Int) {

    val isEmpty: Boolean get() = groups.isEmpty()
}

internal data class TypedPlan(
    val groups: List<PlannedTaskGroup>,
    val tested: Int,
    val compiled: Int,
) {
    fun render(): Plan = Plan(TaskPlanner.render(groups), tested, compiled)
}

object TaskPlanner {

    fun groups(modules: List<ModuleInfo>, task: String): List<TaskGroup> =
        modules.distinctOwners()
            .groupBy { it.systemId to it.executionRoot }
            .flatMap { (key, group) ->
                typedGroups(
                    systemId = key.first,
                    root = key.second,
                    tasks = group.map { it.plannedNamed(task) },
                )
            }
            .let(::render)

    fun plan(changed: List<ModuleInfo>, consumers: List<ModuleInfo>): Plan =
        typedPlan(changed, consumers).render()

    internal fun typedPlan(changed: List<ModuleInfo>, consumers: List<ModuleInfo>): TypedPlan {
        val tasks = LinkedHashMap<Pair<String, String>, MutableList<PlannedTask>>()

        val changedModules = changed.distinctOwners()
        val tested = changedModules.filter { it.hasTests }
        tested.forEach { module ->
            tasks.getOrPut(module.systemId to module.executionRoot) { mutableListOf() }.add(module.plannedTest())
        }

        val testedKeys = tested.map { it.ownerKey() }.toSet()
        val changedKeys = changedModules.map { it.ownerKey() }.toSet()
        val compiled = consumers.distinctOwners()
            .filter { it.compileTask != null }
            .filter { it.ownerKey() !in testedKeys }
            .filter { it.ownerKey() !in changedKeys }
        compiled.forEach { module ->
            val task = module.plannedCompile() ?: return@forEach
            tasks.getOrPut(module.systemId to module.executionRoot) { mutableListOf() }.add(task)
        }

        return TypedPlan(
            groups = tasks.flatMap { (key, value) ->
                typedGroups(key.first, key.second, value)
            },
            tested = tested.size,
            compiled = compiled.size,
        )
    }

    internal fun typedGroups(
        systemId: String,
        root: String,
        tasks: List<PlannedTask>,
    ): List<PlannedTaskGroup> = tasks.compatibleSelections()
        .groupBy { it.kind to it.selection }
        .map { (key, group) ->
            PlannedTaskGroup(
                systemId = systemId,
                root = root,
                kind = key.first,
                selection = key.second,
                tasks = group,
            )
        }

    internal fun render(groups: List<PlannedTaskGroup>): List<TaskGroup> =
        groups.groupBy { it.systemId to it.root }
            .map { (key, group) ->
                TaskGroup(
                    systemId = key.first,
                    root = key.second,
                    tasks = group.flatMap(PlannedTaskGroup::tasks).map(PlannedTask::path).distinct(),
                )
            }

    private fun List<ModuleInfo>.distinctOwners(): List<ModuleInfo> =
        groupBy { it.ownerKey() }.values.map { modules ->
            val first = modules.first()
            val executionCoordinates = modules.map { it.executionRoot to it.executionId }.distinct()
            if (executionCoordinates.size == 1) {
                first
            } else {
                first.copy(executionRoot = first.buildRoot, executionId = first.id)
            }
        }

    private fun List<PlannedTask>.compatibleSelections(): List<PlannedTask> =
        groupBy { it.kind to it.path }.values.map { tasks ->
            val first = tasks.first()
            val selections = tasks.map(PlannedTask::selection).distinct()
            if (selections.size == 1) first else first.copy(selection = TestSelection.All)
        }

    private fun ModuleInfo.ownerKey(): Triple<String, String, String> = Triple(systemId, buildRoot, id)
}
