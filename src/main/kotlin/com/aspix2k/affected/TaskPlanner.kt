package com.aspix2k.affected

data class ModuleInfo(
    val gradlePath: String,
    val buildRoot: String,
    val isAndroid: Boolean,
    val hasTests: Boolean,
) {
    fun testTask(): String = if (isAndroid) "$gradlePath:testDebugUnitTest" else "$gradlePath:test"

    fun compileTask(): String =
        if (isAndroid) "$gradlePath:compileDebugUnitTestKotlin" else "$gradlePath:compileTestKotlin"
}

data class Plan(val tasksByRoot: Map<String, List<String>>, val tested: Int, val compiled: Int) {

    val isEmpty: Boolean get() = tasksByRoot.isEmpty()
}

object TaskPlanner {

    fun plan(changed: List<ModuleInfo>, consumers: List<ModuleInfo>): Plan {
        val tasks = LinkedHashMap<String, MutableList<String>>()

        val tested = changed.distinct().filter { it.hasTests }
        tested.forEach { module ->
            tasks.getOrPut(module.buildRoot) { mutableListOf() }.add(module.testTask())
        }

        val testedPaths = tested.map { it.gradlePath to it.buildRoot }.toSet()
        val compiled = consumers.distinct()
            .filter { (it.gradlePath to it.buildRoot) !in testedPaths }
            .filter { it !in changed }
        compiled.forEach { module ->
            tasks.getOrPut(module.buildRoot) { mutableListOf() }.add(module.compileTask())
        }

        return Plan(
            tasksByRoot = tasks.mapValues { (_, value) -> value.distinct() },
            tested = tested.size,
            compiled = compiled.size,
        )
    }
}
