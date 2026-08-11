package com.aspix2k.affected.build

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

object NodeWorkspaces {

    const val TEST = "test"

    fun parse(root: File): List<BuildModule> {
        val patterns = patternsOf(root) ?: return emptyList()
        val rootPath = root.invariantSeparatorsPath
        val rootManifest = File(root, "package.json").takeIf { it.isFile }
        val manifests = manifestFiles(root, patterns) ?: return emptyList()

        val described = manifests
            .mapNotNull { manifest ->
                val text = ManifestSearch.readText(manifest) ?: return emptyList()
                val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                    ?: return emptyList()
                describe(root, manifest, json, root.name.takeIf { manifest == rootManifest })
            }
            .filter { entry ->
                entry.executionId != "." || patterns.isEmpty() || entry.hasTests || entry.typed
            }
        val nameCounts = described.groupingBy { it.name }.eachCount()
        val ids = described.associateWith { entry ->
            if (nameCounts.getValue(entry.name) == 1) {
                entry.name
            } else {
                entry.directory.removePrefix("$rootPath/").ifEmpty { "." }
            }
        }
        val idsByName = described.groupBy({ it.name }, { ids.getValue(it) })

        return described.map { entry ->
            val dependencies = entry.dependencies
                .flatMapTo(HashSet()) { dependency ->
                    idsByName[dependency].orEmpty().map { "$rootPath|$it" }
                }
            val runnable = entry.hasTests || entry.typed
            val id = ids.getValue(entry)

            BuildModule(
                id = id,
                root = rootPath,
                contentRoots = listOf(entry.directory),
                testTask = if (entry.hasTests) TEST else "typecheck",
                compileTask = "typecheck".takeIf { entry.typed },
                hasTests = runnable,
                dependencies = dependencies - "$rootPath|$id",
                executionId = entry.executionId,
            )
        }
    }

    internal fun manifestFiles(root: File): List<File>? {
        val patterns = patternsOf(root) ?: return null
        return manifestFiles(root, patterns)
    }

    private fun manifestFiles(root: File, patterns: List<String>): List<File>? {
        val matchers = patterns.map { workspacePattern(root, it) ?: return null }
        val candidates = nodePackageManifests(root, workspaceSearchDepth(patterns)) ?: return null
        val workspaceManifests = candidates.filter { manifest ->
            val relative = root.toPath().toAbsolutePath().normalize()
                .relativize(manifest.parentFile.toPath().toAbsolutePath().normalize())
            matchers.any { !it.excluded && it.matches(relative) } &&
                matchers.none { it.excluded && it.matches(relative) }
        }
        val rootManifest = File(root, "package.json").takeIf { it.isFile }
        return (workspaceManifests + listOfNotNull(rootManifest)).distinct()
    }

    private data class Described(
        val name: String,
        val directory: String,
        val dependencies: Set<String>,
        val hasTests: Boolean,
        val typed: Boolean,
        val executionId: String,
    )

    private fun describe(root: File, manifest: File, json: JsonObject, defaultName: String?): Described? {
        val name = json.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: defaultName ?: return null
        val directory = manifest.parentFile ?: return null

        val dependencies = listOf("dependencies", "devDependencies", "peerDependencies")
            .map { objectKeys(json, it) ?: return null }
            .flatten()
            .toHashSet()

        val scripts = objectKeys(json, "scripts") ?: return null

        return Described(
            name = name,
            directory = directory.invariantSeparatorsPath,
            dependencies = dependencies,
            hasTests = "test" in scripts || hasTestSources(directory),
            typed = File(directory, "tsconfig.json").isFile,
            executionId = if (directory == root) "." else name,
        )
    }

    private fun hasTestSources(directory: File): Boolean =
        TEST_DIRS.any { File(directory, it).isDirectory }

    private fun patternsOf(root: File): List<String>? {
        val fromPnpm = pnpmPatterns(root) ?: return null
        if (fromPnpm.isNotEmpty()) return fromPnpm
        return packagePatterns(root)
    }

    private fun objectKeys(json: JsonObject, name: String): Set<String>? {
        val value = json.get(name) ?: return emptySet()
        return value.takeIf { it.isJsonObject }?.asJsonObject?.keySet()
    }

    private val TEST_DIRS = listOf("__tests__", "test", "tests", "spec")
}

private fun pnpmPatterns(root: File): List<String>? {
    val file = File(root, "pnpm-workspace.yaml")
    if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyList()
    if (!file.isRegularFileNoFollow()) return null
    return readPnpm(file)
}

private fun packagePatterns(root: File): List<String>? {
    val manifest = File(root, "package.json").takeIf(File::isRegularFileNoFollow) ?: return emptyList()
    val text = ManifestSearch.readText(manifest) ?: return null
    val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return emptyList()
    val workspaces = json.get("workspaces") ?: return emptyList()
    val packages = if (workspaces.isJsonObject) {
        workspaces.asJsonObject.get("packages") ?: return emptyList()
    } else {
        workspaces
    }
    if (!packages.isJsonArray) return null
    return packages.asJsonArray.map { element ->
        element.takeIf { it.isJsonPrimitive }?.asString ?: return null
    }
}

private fun readPnpm(file: File): List<String>? {
    val patterns = mutableListOf<String>()
    var inPackages = false
    val lines = ManifestSearch.readText(file)?.lineSequence() ?: return null
    for (line in lines) {
        val parsed = parsePnpmLine(line, inPackages) ?: return null
        inPackages = parsed.first
        parsed.second?.let(patterns::add)
    }
    return patterns
}

private fun parsePnpmLine(line: String, inPackages: Boolean): Pair<Boolean, String?>? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith('#')) return inPackages to null
    if (trimmed.startsWith("packages:")) {
        return if (trimmed.removePrefix("packages:").isBlank()) true to null else null
    }
    if (!inPackages) return false to null
    if (trimmed.startsWith("- ")) {
        val pattern = trimmed.removePrefix("- ").trim('\'', '"')
        return pattern.takeIf(String::isNotEmpty)?.let { true to it }
    }
    return if (line.firstOrNull()?.isWhitespace() == true) null else false to null
}

private data class WorkspacePattern(
    val excluded: Boolean,
    val matches: (java.nio.file.Path) -> Boolean,
)

private fun workspacePattern(root: File, pattern: String): WorkspacePattern? = runCatching {
    val trimmed = pattern.trim()
    val excluded = trimmed.startsWith('!')
    val clean = trimmed.removePrefix("!").removePrefix("./").removeSuffix("/")
    if (clean.isEmpty()) return null
    val matcher = root.toPath().fileSystem.getPathMatcher("glob:$clean")
    WorkspacePattern(excluded) { path -> matcher.matches(path) }
}.getOrNull()

private fun workspaceSearchDepth(patterns: List<String>): Int {
    val paths = patterns.map { it.trim().removePrefix("!").removePrefix("./").removeSuffix("/") }
    if (paths.any { it.split('/').contains("**") }) return Int.MAX_VALUE
    return paths.maxOfOrNull { it.split('/').size } ?: 0
}

private fun nodePackageManifests(root: File, maxDepth: Int): List<File>? {
    val manifests = ArrayList<File>()
    val queue = ArrayDeque<Pair<File, Int>>()
    queue += root to 0
    var visited = 0
    while (queue.isNotEmpty()) {
        if (visited++ >= MAX_WORKSPACE_DIRECTORIES) return null
        val (directory, depth) = queue.removeFirst()
        if (!scanNodeDirectory(directory, depth, maxDepth, visited, manifests, queue)) return null
    }
    return manifests
}

private fun scanNodeDirectory(
    directory: File,
    depth: Int,
    maxDepth: Int,
    visited: Int,
    manifests: MutableList<File>,
    queue: ArrayDeque<Pair<File, Int>>,
): Boolean {
    val children = directory.listFiles() ?: return false
    if (children.size > MAX_WORKSPACE_DIRECTORIES) return false
    for (child in children) {
        if (!collectNodeChild(child, depth, maxDepth, manifests, queue)) return false
        if (queue.size + visited > MAX_WORKSPACE_DIRECTORIES) return false
    }
    return true
}

private fun collectNodeChild(
    child: File,
    depth: Int,
    maxDepth: Int,
    manifests: MutableList<File>,
    queue: ArrayDeque<Pair<File, Int>>,
): Boolean {
    val path = child.toPath()
    if (Files.isSymbolicLink(path)) return safeNodeSymlink(child)
    when {
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && child.name == "package.json" -> manifests += child
        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && depth < maxDepth && nodeCanEnter(child) ->
            queue += child to depth + 1
    }
    return true
}

private fun safeNodeSymlink(child: File): Boolean =
    !nodeCanEnter(child) || (!Files.isDirectory(child.toPath()) && child.name != "package.json")

private fun nodeCanEnter(directory: File): Boolean =
    !directory.name.startsWith('.') && directory.name !in NODE_SKIPPED_DIRECTORIES

private const val MAX_WORKSPACE_DIRECTORIES = 4096
private val NODE_SKIPPED_DIRECTORIES = setOf(
    ".venv", "venv", "node_modules", "vendor", "build", "out", "target", "bin", "obj", "dist", "coverage",
    "DerivedData", "Pods", "__pycache__", ".tox", ".cache", "cmake-build-debug", "cmake-build-release",
)
