package com.aspix2k.affected.build

import java.io.File

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
        var projectHasTests = false

        lists.forEach { file ->
            val text = ManifestSearch.readText(file) ?: return emptyList()
            val directory = file.parentFile?.invariantSeparatorsPath ?: return@forEach

            TARGET.findAll(text).forEach { declared.putIfAbsent(it.groupValues[1], directory) }
            if (TESTS.containsMatchIn(text)) projectHasTests = true
            LINKS.findAll(text).forEach { match ->
                val target = match.groupValues[1]
                val referenced = match.groupValues[2]
                    .split(Regex("""\s+"""))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it.uppercase() !in KEYWORDS }
                links.getOrPut(target) { mutableSetOf() } += referenced
            }
        }

        return declared.map { (name, directory) ->
            val dependencies = links[name].orEmpty()
                .filter { it in declared.keys }
                .mapTo(HashSet()) { moduleDependencyKey("CMAKE", rootPath, it) }

            BuildModule(
                id = name,
                root = rootPath,
                contentRoots = listOf(directory),
                testTask = if (projectHasTests) TEST else BUILD,
                compileTask = BUILD,
                hasTests = true,
                dependencies = dependencies - moduleDependencyKey("CMAKE", rootPath, name),
                systemId = "CMAKE",
            )
        }
    }

    private fun findLists(root: File): List<File> = ManifestSearch.find(root, "CMakeLists.txt")
}
