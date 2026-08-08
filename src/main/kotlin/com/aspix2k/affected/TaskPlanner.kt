package com.aspix2k.affected

data class ModuleInfo(
    val id: String,
    val buildRoot: String,
    val testTask: String,
    val compileTask: String,
    val hasTests: Boolean,
) {
    fun test(): String = "$id:$testTask"

    fun compile(): String = "$id:$compileTask"
}

data class Plan(val tasksByRoot: Map<String, List<String>>, val tested: Int, val compiled: Int) {

    val isEmpty: Boolean get() = tasksByRoot.isEmpty()
}

object TaskPlanner {

    fun plan(changed: List<ModuleInfo>, consumers: List<ModuleInfo>): Plan {
        val tasks = LinkedHashMap<String, MutableList<String>>()

        val tested = changed.distinct().filter { it.hasTests }
        tested.forEach { module ->
            tasks.getOrPut(module.buildRoot) { mutableListOf() }.add(module.test())
        }

        val testedIds = tested.map { it.id to it.buildRoot }.toSet()
        val compiled = consumers.distinct()
            .filter { (it.id to it.buildRoot) !in testedIds }
            .filter { it !in changed }
        compiled.forEach { module ->
            tasks.getOrPut(module.buildRoot) { mutableListOf() }.add(module.compile())
        }

        return Plan(
            tasksByRoot = tasks.mapValues { (_, value) -> value.distinct() },
            tested = tested.size,
            compiled = compiled.size,
        )
    }
}
