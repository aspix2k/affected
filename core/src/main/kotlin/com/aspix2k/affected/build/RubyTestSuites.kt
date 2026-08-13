package com.aspix2k.affected.build

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.ArrayDeque

internal enum class RubyTestRunner(val command: String, val gem: String = command) {
    RSPEC("rspec"),
    MINITEST("minitest"),
    TEST_UNIT("test-unit"),
}

internal object RubyTestSuites {

    private const val PREFIX = "test-"
    private const val FALLBACK_PREFIX = "fallback-"
    private const val MAX_SUITE_ENTRIES = 16_384

    fun task(directory: File, locked: Set<RubyTestRunner>?): String? {
        val hasSpec = suitePresent(File(directory, "spec")) ?: return null
        val hasTest = suitePresent(File(directory, "test")) ?: return null
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
        if (!lockPresent(lock)) return FALLBACK_PREFIX + RubyTestRunner.RSPEC.command
        val runners = lockedRunners(root)?.takeIf(Set<RubyTestRunner>::isNotEmpty) ?: return INVALID
        return FALLBACK_PREFIX + encode(runners).removePrefix(PREFIX)
    }

    fun lockedRunners(root: File): Set<RubyTestRunner>? = RubyLockFile.runners(root)

    fun runners(task: String): List<RubyTestRunner>? {
        if (task == RubyGems.TEST) return listOf(RubyTestRunner.RSPEC)
        val prefix = listOf(PREFIX, FALLBACK_PREFIX).singleOrNull(task::startsWith) ?: return null
        val commands = task.removePrefix(prefix).split('+')
        if (commands.isEmpty() || commands.any(String::isEmpty) || commands.distinct().size != commands.size) {
            return null
        }
        val parsed = commands.map { command ->
            RubyTestRunner.entries.singleOrNull { it.command == command } ?: return null
        }
        return parsed.takeIf { it == it.sortedBy(RubyTestRunner::ordinal) }
    }

    private fun encode(runners: Collection<RubyTestRunner>): String =
        PREFIX + runners.sortedBy(RubyTestRunner::ordinal).joinToString("+") { it.command }

    const val INVALID = "invalid"

    fun isFallback(task: String): Boolean = task.startsWith(FALLBACK_PREFIX)

    fun suitePresent(directory: File): Boolean? {
        val path = directory.toPath()
        if (Files.isSymbolicLink(path)) return null
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return null
        return safeTree(path).takeIf { it } ?: return null
    }

    fun invalidLock(root: File, runners: Set<RubyTestRunner>?): Boolean =
        lockPresent(File(root, "Gemfile.lock")) && runners == null

    private fun lockPresent(lock: File): Boolean =
        Files.exists(lock.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(lock.toPath())

    private fun safeTree(root: Path): Boolean = runCatching {
        val pending = ArrayDeque<Path>()
        pending.add(root)
        var entries = 0
        var valid = true
        while (valid && pending.isNotEmpty()) {
            Files.newDirectoryStream(pending.removeFirst()).use { children ->
                for (child in children) {
                    if (!valid) break
                    entries += 1
                    val unreadable = entries > MAX_SUITE_ENTRIES ||
                        Files.isSymbolicLink(child) ||
                        (
                            !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) &&
                                !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)
                            )
                    when {
                        unreadable -> valid = false
                        Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> pending.add(child)
                    }
                }
            }
        }
        valid
    }.getOrDefault(false)
}

internal fun RubyTestRunner.supports(version: String): Boolean {
    val stable = Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(version)?.groupValues ?: return false
    val major = stable[1].toIntOrNull() ?: return false
    val minor = stable[2].toIntOrNull() ?: return false
    return when (this) {
        RubyTestRunner.RSPEC -> major == 3 && minor == 13
        RubyTestRunner.MINITEST -> major == 6 && minor == 0
        RubyTestRunner.TEST_UNIT -> major == 3 && minor == 7
    }
}
