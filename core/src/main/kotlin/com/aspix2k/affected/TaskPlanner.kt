package com.aspix2k.affected

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
}

data class TaskGroup(val systemId: String, val root: String, val tasks: List<String>)

data class Plan(val groups: List<TaskGroup>, val tested: Int, val compiled: Int) {

    val isEmpty: Boolean get() = groups.isEmpty()
}

object TaskPlanner {

    fun groups(modules: List<ModuleInfo>, task: String): List<TaskGroup> =
        modules.distinctOwners()
            .groupBy { it.systemId to it.executionRoot }
            .map { (key, group) ->
                TaskGroup(
                    systemId = key.first,
                    root = key.second,
                    tasks = group.map { "${it.executionId}:$task" }.distinct(),
                )
            }

    fun plan(changed: List<ModuleInfo>, consumers: List<ModuleInfo>): Plan {
        val tasks = LinkedHashMap<Pair<String, String>, MutableList<String>>()

        val changedModules = changed.distinctOwners()
        val tested = changedModules.filter { it.hasTests }
        tested.forEach { module ->
            tasks.getOrPut(module.systemId to module.executionRoot) { mutableListOf() }.add(module.test())
        }

        val testedKeys = tested.map { it.ownerKey() }.toSet()
        val changedKeys = changedModules.map { it.ownerKey() }.toSet()
        val compiled = consumers.distinctOwners()
            .filter { it.compileTask != null }
            .filter { it.ownerKey() !in testedKeys }
            .filter { it.ownerKey() !in changedKeys }
        compiled.forEach { module ->
            val task = module.compile() ?: return@forEach
            tasks.getOrPut(module.systemId to module.executionRoot) { mutableListOf() }.add(task)
        }

        return Plan(
            groups = tasks.map { (key, value) -> TaskGroup(key.first, key.second, value.distinct()) },
            tested = tested.size,
            compiled = compiled.size,
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

    private fun ModuleInfo.ownerKey(): Triple<String, String, String> = Triple(systemId, buildRoot, id)
}
