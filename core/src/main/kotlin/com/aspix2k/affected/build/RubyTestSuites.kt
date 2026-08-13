package com.aspix2k.affected.build

import java.io.File

internal enum class RubyTestRunner(val command: String, val gem: String = command) {
    RSPEC("rspec"),
    MINITEST("minitest"),
    TEST_UNIT("test-unit"),
}

internal object RubyTestSuites {

    private const val PREFIX = "test-"
    private const val FALLBACK_PREFIX = "fallback-"
    private val LOCKED_SPEC = Regex("""^    (rspec|minitest|test-unit) \(([^)]+)\)$""")
    private val LOCKED_DEPENDENCY = Regex("""^  ([A-Za-z0-9][A-Za-z0-9._-]*)(?: \([^)]+\))?!?(?: \S+)?$""")

    fun task(directory: File, locked: Set<RubyTestRunner>?): String? {
        val hasSpec = File(directory, "spec").isDirectory
        val hasTest = File(directory, "test").isDirectory
        if (!hasSpec && !hasTest) return RubyGems.TEST
        val runners = linkedSetOf<RubyTestRunner>()
        if (hasSpec) runners += RubyTestRunner.RSPEC
        if (hasTest) {
            val testRunners = locked?.filter { it != RubyTestRunner.RSPEC }.orEmpty()
            if (testRunners.isEmpty()) return null
            runners += testRunners
        }
        if (locked != null && runners.any { it !in locked }) return null
        return encode(runners)
    }

    fun fallbackTask(root: File): String {
        val lock = File(root, "Gemfile.lock")
        if (lock.exists() && lockedRunners(root) == null) return INVALID
        return FALLBACK_PREFIX + encode(RubyTestRunner.entries).removePrefix(PREFIX)
    }

    fun lockedRunners(root: File): Set<RubyTestRunner>? {
        val text = ManifestSearch.readText(File(root, "Gemfile.lock")) ?: return null
        if (text.contains("<<<<<<<") || text.contains("=======") || text.contains(">>>>>>>")) return null
        val lines = text.splitToSequence('\n').map { it.removeSuffix("\r") }.toList()
        val dependencySections = lines.withIndex().filter { it.value == "DEPENDENCIES" }.map { it.index }
        if (dependencySections.size != 1) return null
        val versions = lines.mapNotNull { line ->
            LOCKED_SPEC.matchEntire(line)?.let { it.groupValues[1] to it.groupValues[2] }
        }.groupBy({ it.first }, { it.second })
        val names = linkedSetOf<String>()
        for (line in lines.drop(dependencySections.single() + 1)) {
            if (line.isBlank()) continue
            if (!line.startsWith(' ')) break
            val match = LOCKED_DEPENDENCY.matchEntire(line) ?: return null
            match.groupValues[1].takeIf { name -> RubyTestRunner.entries.any { it.gem == name } }?.let(names::add)
        }
        return names.mapTo(linkedSetOf()) { name ->
            val runner = RubyTestRunner.entries.single { it.gem == name }
            val candidates = versions[name].orEmpty()
            if (candidates.size != 1 || !runner.supports(candidates.single())) return null
            runner
        }
    }

    fun runners(task: String): List<RubyTestRunner>? {
        if (task == RubyGems.TEST) return listOf(RubyTestRunner.RSPEC)
        val prefix = listOf(PREFIX, FALLBACK_PREFIX).singleOrNull(task::startsWith) ?: return null
        val commands = task.removePrefix(prefix).split('+')
        if (commands.isEmpty() || commands.any(String::isEmpty) || commands.distinct().size != commands.size) return null
        val parsed = commands.map { command -> RubyTestRunner.entries.singleOrNull { it.command == command } ?: return null }
        return parsed.takeIf { it == it.sortedBy(RubyTestRunner::ordinal) }
    }

    private fun encode(runners: Collection<RubyTestRunner>): String =
        PREFIX + runners.sortedBy(RubyTestRunner::ordinal).joinToString("+") { it.command }

    const val INVALID = "invalid"

    fun isFallback(task: String): Boolean = task.startsWith(FALLBACK_PREFIX)
}

private fun RubyTestRunner.supports(version: String): Boolean {
    val stable = Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(version)?.groupValues ?: return false
    val major = stable[1].toIntOrNull() ?: return false
    val minor = stable[2].toIntOrNull() ?: return false
    return when (this) {
        RubyTestRunner.RSPEC -> major == 3 && minor == 13
        RubyTestRunner.MINITEST -> major == 6 && minor == 0
        RubyTestRunner.TEST_UNIT -> major == 3 && minor == 7
    }
}
