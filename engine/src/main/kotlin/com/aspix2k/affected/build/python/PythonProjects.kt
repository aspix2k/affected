package com.aspix2k.affected.build.python

import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.ManifestSearch
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

object PythonProjects {

    const val TEST = "test"
    const val TYPECHECK = "typecheck"

    fun parse(root: File): List<BuildModule> {
        val rootPath = root.invariantSeparatorsPath
        val manifests = findManifests(root)
        if (manifests.isEmpty()) return emptyList()

        val manifestRoots = manifests.mapNotNull { it.parentFile?.toPath()?.toAbsolutePath()?.normalize() }.toSet()
        val described = manifests.map { manifest ->
            val directory = manifest.parentFile?.toPath()?.toAbsolutePath()?.normalize() ?: return emptyList()
            val nestedRoots = manifestRoots.filterTo(HashSet()) { it != directory && it.startsWith(directory) }
            describe(manifest, nestedRoots) ?: return emptyList()
        }
        val names = described.map { it.name }.toSet()
        if (names.size != described.size) return emptyList()
        val local = names.associateBy(::distributionName)

        return described.map { entry ->
            val dependencies = entry.dependencies
                .mapNotNull { local[distributionName(it)] }
                .mapTo(HashSet()) { "$rootPath|$it" }
            val runnable = entry.hasTests || entry.typed

            BuildModule(
                id = entry.name,
                root = rootPath,
                contentRoots = listOf(entry.directory),
                testTask = if (entry.hasTests) TEST else TYPECHECK,
                compileTask = TYPECHECK.takeIf { entry.typed },
                hasTests = runnable,
                dependencies = dependencies - "$rootPath|${entry.name}",
                executionId = if (entry.directory == rootPath) "." else entry.name,
            )
        }
    }

    private data class Described(
        val name: String,
        val directory: String,
        val dependencies: Set<String>,
        val hasTests: Boolean,
        val typed: Boolean,
    )

    private fun describe(manifest: File, nestedRoots: Set<Path>): Described? {
        val directory = manifest.parentFile ?: return null
        val text = ManifestSearch.readText(manifest) ?: return null
        val lines = text.lineSequence().toList()

        val name = valueOf(lines, "name") ?: return null

        return Described(
            name = name,
            directory = directory.invariantSeparatorsPath,
            dependencies = dependenciesOf(lines) + groupedDependencies(text),
            hasTests = hasTests(directory, nestedRoots) ?: return null,
            typed = lines.any { it.trim().startsWith("[tool.mypy") } || File(directory, "mypy.ini").isFile,
        )
    }

    private fun valueOf(lines: List<String>, key: String): String? = lines
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key ") && it.contains('=') }
        ?.substringAfter('=')
        ?.trim()
        ?.trim('"', '\'')
        ?.takeIf { it.isNotEmpty() }

    private fun dependenciesOf(lines: List<String>): Set<String> {
        val result = HashSet<String>()
        var inList = false

        lines.forEach { raw ->
            val line = raw.trim()
            when {
                DEPENDENCY_KEYS.any { line.startsWith(it) } -> {
                    inList = true
                    result += namesIn(line.substringAfter('='))
                }
                inList && line.startsWith('[') && line.endsWith(']') && !line.contains('"') -> inList = false
                inList -> result += namesIn(line)
            }
            if (inList && line.endsWith("]") && !line.startsWith("[")) inList = false
        }
        return result
    }

    private fun groupedDependencies(text: String): Set<String> = runCatching {
        val manifest = Toml.parse(text).takeUnless { it.hasErrors() } ?: return emptySet()
        val poetry = manifest.get("tool.poetry") as? TomlTable
        val poetryGroups = (poetry?.get("group") as? TomlTable)?.let { groups ->
            groups.keySet().mapNotNull { (groups.get(listOf(it)) as? TomlTable)?.get("dependencies") }
        }.orEmpty()
        val poetryTables = listOf(poetry?.get("dependencies"), poetry?.get("dev-dependencies")) + poetryGroups
        val groups = REQUIREMENT_GROUPS.mapNotNull { manifest.get(it) as? TomlTable }
            .flatMap { group -> group.keySet().map { group.get(listOf(it)) } }
        val requirements = (groups + manifest.get("tool.uv.dev-dependencies"))
            .filterIsInstance<TomlArray>()
            .flatMap { array -> array.toList().filterIsInstance<String>() }
        val poetryNames = poetryTables.filterIsInstance<TomlTable>().flatMap { it.keySet() }
        requirements.flatMapTo(HashSet(), ::namesIn) + poetryNames
    }.getOrDefault(emptySet())

    private fun distributionName(name: String): String = name.lowercase().replace(NAME_SEPARATORS, "-")

    private fun namesIn(fragment: String): List<String> = fragment
        .split(',')
        .map { it.trim().trim('[', ']').trim('"', '\'') }
        .filter { it.isNotEmpty() }
        .map { entry -> entry.takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' } }
        .filter { it.isNotEmpty() }

    private fun hasTests(directory: File, nestedRoots: Set<Path>): Boolean? {
        for (name in TEST_DIRS) {
            val candidate = File(directory, name).toPath()
            if (Files.isSymbolicLink(candidate)) return null
            if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) return true
        }
        return ManifestSearch.anyFile(directory, nestedRoots) {
            it.name.startsWith("test_") && it.extension == "py"
        }
    }

    private fun findManifests(root: File): List<File> = ManifestSearch.find(root, setOf("pyproject.toml"))

    private val DEPENDENCY_KEYS = listOf("dependencies =", "dependencies=", "install_requires =")
    private val TEST_DIRS = listOf("tests", "test")
    private val REQUIREMENT_GROUPS = listOf("project.optional-dependencies", "dependency-groups")
    private val NAME_SEPARATORS = Regex("[-_.]+")
}
