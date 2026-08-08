package com.aspix2k.affected.build

import java.io.File

/**
 * Targets of a CMake project.
 *
 * CMakeLists.txt is a script, but the three commands that matter follow a fixed
 * shape and are read directly: what a target is called, which libraries it links,
 * and whether it is registered as a test. Anything else is ignored rather than
 * guessed at.
 */
object CMakeTargets {

    const val TEST = "test"
    const val BUILD = "build"

    private val TARGET = Regex(
        """add_(?:executable|library)\s*\(\s*([A-Za-z0-9_\-.]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val LINKS = Regex(
        """target_link_libraries\s*\(\s*([A-Za-z0-9_\-.]+)([^)]*)\)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val TESTS = Regex(
        """add_test\s*\(\s*(?:NAME\s+)?([A-Za-z0-9_\-.]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val KEYWORDS = setOf("PUBLIC", "PRIVATE", "INTERFACE", "STATIC", "SHARED", "MODULE", "ALIAS")

    fun parse(root: File): List<BuildModule> {
        val rootPath = root.invariantSeparatorsPath
        val lists = findLists(root)
        if (lists.isEmpty()) return emptyList()

        val declared = LinkedHashMap<String, String>()
        val links = HashMap<String, MutableSet<String>>()
        val tested = HashSet<String>()

        lists.forEach { file ->
            val text = file.readText()
            val directory = file.parentFile?.invariantSeparatorsPath ?: return@forEach

            TARGET.findAll(text).forEach { declared.putIfAbsent(it.groupValues[1], directory) }
            TESTS.findAll(text).forEach { tested += it.groupValues[1] }
            LINKS.findAll(text).forEach { match ->
                val target = match.groupValues[1]
                val referenced = match.groupValues[2]
                    .split(Regex("""\s+"""))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it.uppercase() !in KEYWORDS }
                links.getOrPut(target) { mutableSetOf() } += referenced
            }
        }

        if (declared.size < 2) return emptyList()

        return declared.map { (name, directory) ->
            val dependencies = links[name].orEmpty()
                .filter { it in declared.keys }
                .mapTo(HashSet()) { "$rootPath|$it" }

            BuildModule(
                id = name,
                root = rootPath,
                contentRoots = listOf(directory),
                testTask = TEST,
                compileTask = BUILD,
                hasTests = name in tested,
                dependencies = dependencies - "$rootPath|$name",
            )
        }
    }

    private fun findLists(root: File): List<File> = root.walkTopDown()
        .onEnter { it.name != ".git" && it.name != "build" && it.name != "cmake-build-debug" }
        .filter { it.isFile && it.name == "CMakeLists.txt" }
        .toList()
}
