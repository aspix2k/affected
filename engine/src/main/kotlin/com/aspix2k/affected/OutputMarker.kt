package com.aspix2k.affected

internal class OutputMarker(private val reached: (String) -> Boolean) {

    private val pending = StringBuilder()

    @Volatile
    var seen: Boolean = false
        private set

    @Synchronized
    fun accept(text: String) {
        if (seen) return
        pending.append(text)
        var end = pending.indexOf("\n")
        while (end >= 0) {
            val line = pending.substring(0, end).trimEnd('\r')
            pending.delete(0, end + 1)
            if (reached(line)) {
                seen = true
                pending.setLength(0)
                return
            }
            end = pending.indexOf("\n")
        }
        if (pending.length > MAX_PENDING) pending.setLength(0)
    }

    private companion object {
        const val MAX_PENDING = 16 * 1024
    }
}

internal fun gradleTaskReached(planned: Collection<String>): (String) -> Boolean {
    val tasks = planned.filterTo(HashSet()) { it.startsWith(":") }
    return { line ->
        line.startsWith(GRADLE_TASK_PREFIX) && line.removePrefix(GRADLE_TASK_PREFIX).substringBefore(' ') in tasks
    }
}

internal fun mavenTestsReached(line: String): Boolean = MAVEN_TEST_GOAL.containsMatchIn(line)

private const val GRADLE_TASK_PREFIX = "> Task "
private val MAVEN_TEST_GOAL = Regex("""--- \S*(?:surefire|failsafe)\S*:(?:test|integration-test) """)
