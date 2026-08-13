package com.aspix2k.affected.build

import java.io.File

internal object RubyLockFile {

    private val LOCKED_SPEC = Regex("""^    (rspec|minitest|test-unit) \(([^)]+)\)$""")
    private val LOCKED_DEPENDENCY = Regex("""^  ([A-Za-z0-9][A-Za-z0-9._-]*)(?: \([^)]+\))?!?(?: \S+)?$""")

    fun runners(root: File): Set<RubyTestRunner>? {
        val lines = lockLines(root) ?: return null
        val sections = lockSections(lines) ?: return null
        if (!hasOfficialGemRemote(lines, sections)) return null
        val versions = specVersions(lines, sections)
        val names = dependencyRunnerNames(lines, sections) ?: return null
        return resolveRunners(names, versions)
    }

    private data class LockSections(val gem: Int, val gemEnd: Int, val specs: Int, val dependencies: Int)

    private fun lockLines(root: File): List<String>? {
        val text = ManifestSearch.readText(File(root, "Gemfile.lock")) ?: return null
        if (text.contains("<<<<<<<") || text.contains("=======") || text.contains(">>>>>>>")) return null
        return text.splitToSequence('\n').map { it.removeSuffix("\r") }.toList()
    }

    private fun lockSections(lines: List<String>): LockSections? {
        val dependencySections = headingIndexes(lines, "DEPENDENCIES")
        val gemSections = headingIndexes(lines, "GEM")
        if (dependencySections.size != 1 || gemSections.size != 1) return null
        val gem = gemSections.single()
        val gemEnd = lines.indices.firstOrNull { it > gem && lines[it].isNotBlank() && !lines[it].startsWith(' ') }
            ?: lines.size
        val specSections = lines.subList(gem + 1, gemEnd)
            .withIndex()
            .filter { it.value == "  specs:" }
            .map { it.index + gem + 1 }
        if (specSections.size != 1) return null
        return LockSections(gem, gemEnd, specSections.single(), dependencySections.single())
    }

    private fun headingIndexes(lines: List<String>, heading: String): List<Int> =
        lines.withIndex().filter { it.value == heading }.map { it.index }

    private fun hasOfficialGemRemote(lines: List<String>, sections: LockSections): Boolean =
        lines.subList(sections.gem + 1, sections.specs).filter { it.startsWith("  remote:") } ==
            listOf("  remote: https://rubygems.org/")

    private fun specVersions(lines: List<String>, sections: LockSections): Map<String, List<String>> =
        lines.subList(sections.specs + 1, sections.gemEnd).mapNotNull { line ->
            LOCKED_SPEC.matchEntire(line)?.let { it.groupValues[1] to it.groupValues[2] }
        }.groupBy({ it.first }, { it.second })

    private fun dependencyRunnerNames(lines: List<String>, sections: LockSections): Set<String>? {
        val names = linkedSetOf<String>()
        val block = lines.drop(sections.dependencies + 1).takeWhile { it.isNotBlank() && it.startsWith(' ') }
        for (line in block) {
            val match = LOCKED_DEPENDENCY.matchEntire(line) ?: return null
            val name = match.groupValues[1]
            if (RubyTestRunner.entries.none { it.gem == name }) continue
            if ('!' in line) return null
            names += name
        }
        return names
    }

    private fun resolveRunners(
        names: Set<String>,
        versions: Map<String, List<String>>,
    ): Set<RubyTestRunner>? = names.mapTo(linkedSetOf()) { name ->
        val runner = RubyTestRunner.entries.single { it.gem == name }
        val candidates = versions[name].orEmpty()
        if (candidates.size != 1 || !runner.supports(candidates.single())) return null
        runner
    }
}
