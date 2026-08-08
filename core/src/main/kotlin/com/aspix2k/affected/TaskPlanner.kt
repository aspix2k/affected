package com.aspix2k.affected

data class ModuleInfo(
    val id: String,
    val systemId: String,
    val buildRoot: String,
    val testTask: String,
    val compileTask: String?,
    val hasTests: Boolean,
) {
    fun test(): String = "$id:$testTask"

    fun compile(): String? = compileTask?.let { "$id:$it" }
}

data class TaskGroup(val systemId: String, val root: String, val tasks: List<String>)

data class Plan(val groups: List<TaskGroup>, val tested: Int, val compiled: Int) {

    val isEmpty: Boolean get() = groups.isEmpty()
}

object TaskPlanner {

    fun plan(changed: List<ModuleInfo>, consumers: List<ModuleInfo>): Plan {
        val tasks = LinkedHashMap<Pair<String, String>, MutableList<String>>()

        val tested = changed.distinct().filter { it.hasTests }
        tested.forEach { module ->
            tasks.getOrPut(module.systemId to module.buildRoot) { mutableListOf() }.add(module.test())
        }

        val testedKeys = tested.map { it.id to it.buildRoot }.toSet()
        val compiled = consumers.distinct()
            .filter { it.compileTask != null }
            .filter { (it.id to it.buildRoot) !in testedKeys }
            .filter { it !in changed }
        compiled.forEach { module ->
            val task = module.compile() ?: return@forEach
            tasks.getOrPut(module.systemId to module.buildRoot) { mutableListOf() }.add(task)
        }

        return Plan(
            groups = tasks.map { (key, value) -> TaskGroup(key.first, key.second, value.distinct()) },
            tested = tested.size,
            compiled = compiled.size,
        )
    }
}
