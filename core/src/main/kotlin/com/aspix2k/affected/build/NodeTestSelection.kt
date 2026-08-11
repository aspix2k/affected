package com.aspix2k.affected.build

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal enum class NodeTestRunner(val displayName: String) {
    JEST("Jest"),
    VITEST("Vitest"),
}

internal data class NodeRelatedTestSelection(
    val runner: NodeTestRunner,
    val files: List<String>,
)

internal fun nodeRelatedTestSelections(
    root: File,
    tasks: List<String>,
    buildChanges: BuildChanges,
): Map<String, NodeRelatedTestSelection> = runCatching {
    val rootPath = root.toPath().toAbsolutePath().normalize()
    if (Files.isSymbolicLink(rootPath) || !Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) return emptyMap()
    if (unambiguousNodeManager(root) == null) return emptyMap()

    val modules = NodeWorkspaces.parse(root)
    if (modules.isEmpty()) return emptyMap()
    val testPackages = tasks
        .filter { it.substringAfterLast(':') == NodeWorkspaces.TEST }
        .map { it.substringBeforeLast(':') }
        .distinct()
    if (testPackages.isEmpty()) return emptyMap()

    val changes = buildChanges.files.map { Path.of(it).toAbsolutePath().normalize() }.filter(rootPath::contains)
    val eligible = buildChanges.exactSelectionEligible
        .mapTo(HashSet()) { Path.of(it).toAbsolutePath().normalize() }
    if (changes.isEmpty() || changes.any { globalNodeFallback(rootPath, it) }) return emptyMap()

    val owners = changes.associateWith { changed -> nodeOwner(modules, changed) }
    if (owners.values.any { it == null }) return emptyMap()

    val byExecutionId = modules.associateBy(BuildModule::executionId)
    val moduleRoots = modules.map { Path.of(it.contentRoots.single()).toAbsolutePath().normalize() }.toSet()
    buildMap {
        testPackages.forEach { packageName ->
            val module = byExecutionId[packageName] ?: return@forEach
            val directory = Path.of(module.contentRoots.single()).toAbsolutePath().normalize()
            val packageChanges = owners.filterValues { it == module }.keys
            if (packageChanges.isEmpty()) return@forEach
            if (!eligible.containsAll(packageChanges)) return@forEach
            val selection = relatedSelection(rootPath, directory, moduleRoots - directory, packageChanges)
                ?: return@forEach
            put(packageName, selection)
        }
    }
}.getOrDefault(emptyMap())

private fun Path.contains(path: Path): Boolean = path.startsWith(this)

private fun globalNodeFallback(root: Path, changed: Path): Boolean {
    val relative = root.relativize(changed).toString().replace('\\', '/')
    return '/' !in relative && relative in PACKAGE_FALLBACK_FILES
}

private fun nodeOwner(modules: List<BuildModule>, changed: Path): BuildModule? {
    val candidates = modules.filter { module ->
        module.contentRoots.any { changed.startsWith(Path.of(it).toAbsolutePath().normalize()) }
    }
    val depth = candidates.maxOfOrNull { module ->
        module.contentRoots.maxOf { Path.of(it).toAbsolutePath().normalize().nameCount }
    } ?: return null
    return candidates.singleOrNull { module ->
        module.contentRoots.any { Path.of(it).toAbsolutePath().normalize().nameCount == depth }
    }
}

private fun relatedSelection(
    root: Path,
    directory: Path,
    nestedModules: Set<Path>,
    changes: Set<Path>,
): NodeRelatedTestSelection? {
    if (changes.any { !Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(it) }) return null
    if (changes.any { unsafeNodePath(directory, it) }) return null
    if (changes.any { changed -> changed.fileName.toString() in PACKAGE_FALLBACK_FILES }) return null
    if (changes.any { changed -> changed.fileName.toString() in NODE_CONFIG_FILES }) return null
    if (changes.any { changed -> changed.extension.lowercase() !in RELATED_SOURCE_EXTENSIONS }) return null

    val metadata = nodeRunner(root, directory) ?: return null
    if (!safeStaticNodePackage(directory, nestedModules)) return null
    val relative = changes.map { directory.relativize(it).toString().replace('\\', '/') }.sorted()
    if (relative.any { it.startsWith("../") || it == ".." }) return null
    return NodeRelatedTestSelection(metadata, relative)
}

private fun unsafeNodePath(directory: Path, changed: Path): Boolean =
    directory.relativize(changed).any { segment ->
        val name = segment.toString()
        name in NODE_SCAN_IGNORED || name in NODE_GENERATED_DIRECTORIES || name.startsWith('.')
    }

private val Path.extension: String
    get() = fileName.toString().substringAfterLast('.', "")

private fun nodeRunner(root: Path, directory: Path): NodeTestRunner? {
    val manifest = readJson(directory.resolve("package.json")) ?: return null
    val rootManifest = if (directory == root) manifest else readJson(root.resolve("package.json")) ?: return null
    val manifests = listOf(manifest, rootManifest)
    if (manifests.any(::hasUnsafeRunnerFields) || hasRunnerConfig(root, directory)) return null
    val runner = runnerFromScript(manifest.objectString("scripts", "test")) ?: return null
    val versions = manifests.map { it.dependencyVersions() ?: return null }
    if (versions.any { dependencies -> dependencies.keys.any(TRANSFORM_DEPENDENCIES::contains) }) return null
    val version = versions.firstNotNullOfOrNull { it[runner.packageName] } ?: return null
    return runner.takeIf { it.supports(version) }
}

private fun runnerFromScript(script: String?): NodeTestRunner? =
    when (script?.trim()) {
        "jest" -> NodeTestRunner.JEST
        "vitest", "vitest run", "vitest --run" -> NodeTestRunner.VITEST
        else -> null
    }

private fun hasUnsafeRunnerFields(manifest: JsonObject): Boolean =
    UNSAFE_PACKAGE_FIELDS.any(manifest::has)

private fun hasRunnerConfig(root: Path, directory: Path): Boolean =
    NODE_CONFIG_FILES.any { name ->
        Files.exists(directory.resolve(name), LinkOption.NOFOLLOW_LINKS) ||
            Files.exists(root.resolve(name), LinkOption.NOFOLLOW_LINKS)
    }

private val NodeTestRunner.packageName: String
    get() = when (this) {
        NodeTestRunner.JEST -> "jest"
        NodeTestRunner.VITEST -> "vitest"
    }

private fun NodeTestRunner.supports(version: String): Boolean {
    val match = SIMPLE_VERSION.matchEntire(version.trim()) ?: return false
    val major = match.groupValues[1].toIntOrNull() ?: return false
    return when (this) {
        NodeTestRunner.JEST -> major in 29..30
        NodeTestRunner.VITEST -> major in 2..4
    }
}

private fun readJson(path: Path): JsonObject? {
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
    if (!Files.isReadable(path) || Files.size(path) > MAX_NODE_FILE_BYTES) return null
    val text = runCatching { Files.readString(path, StandardCharsets.UTF_8) }.getOrNull() ?: return null
    return runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
}

private fun JsonObject.objectString(objectName: String, valueName: String): String? {
    val objectValue = get(objectName) ?: return null
    if (!objectValue.isJsonObject) return null
    val value = objectValue.asJsonObject.get(valueName) ?: return null
    return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}

private fun JsonObject.dependencyVersions(): Map<String, String>? {
    val versions = LinkedHashMap<String, String>()
    for (field in DEPENDENCY_FIELDS) {
        val value = get(field) ?: continue
        if (!value.isJsonObject) return null
        for ((name, version) in value.asJsonObject.entrySet()) {
            if (!version.isJsonPrimitive || !version.asJsonPrimitive.isString) return null
            if (versions.put(name, version.asString) != null) return null
        }
    }
    return versions
}

internal fun unambiguousNodeManager(root: File): String? {
    val rootPath = root.toPath()
    if (NODE_MANAGER_FILES.any { name ->
            val path = rootPath.resolve(name)
            Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        }
    ) {
        return null
    }
    if (NODE_UNSUPPORTED_MANAGER_FILES.any { Files.exists(rootPath.resolve(it), LinkOption.NOFOLLOW_LINKS) }) {
        return null
    }
    val manifest = readJson(rootPath.resolve("package.json")) ?: return null
    val packageManager = manifest.get("packageManager")?.let { value ->
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        value.asString.substringBefore('@').takeIf(SUPPORTED_NODE_MANAGERS::contains) ?: return null
    }
    val managers = buildSet {
        if (NODE_PNPM_MARKERS.any { Files.exists(rootPath.resolve(it), LinkOption.NOFOLLOW_LINKS) }) add("pnpm")
        if (Files.exists(rootPath.resolve("yarn.lock"), LinkOption.NOFOLLOW_LINKS)) add("yarn")
        if (NODE_NPM_MARKERS.any { Files.exists(rootPath.resolve(it), LinkOption.NOFOLLOW_LINKS) }) add("npm")
        packageManager?.let(::add)
    }
    return if (managers.size <= 1) managers.singleOrNull() ?: "npm" else null
}

private data class NodeScanBudget(
    var directories: Int = 0,
    var files: Int = 0,
    var bytes: Long = 0,
)

private fun safeStaticNodePackage(directory: Path, nestedModules: Set<Path>): Boolean {
    val queue = ArrayDeque<Pair<Path, Int>>()
    queue += directory to 0
    val budget = NodeScanBudget()
    while (queue.isNotEmpty()) {
        if (budget.directories++ >= MAX_NODE_DIRECTORIES) return false
        val (current, depth) = queue.removeFirst()
        val children = nodeChildren(current) ?: return false
        if (children.size > MAX_NODE_DIRECTORIES) return false
        if (!children.all { child -> safeNodeChild(child, depth, nestedModules, queue, budget) }) return false
    }
    return true
}

private fun nodeChildren(directory: Path): List<Path>? = runCatching {
    Files.newDirectoryStream(directory).use { stream -> stream.toList() }
}.getOrNull()

private fun safeNodeChild(
    child: Path,
    depth: Int,
    nestedModules: Set<Path>,
    queue: ArrayDeque<Pair<Path, Int>>,
    budget: NodeScanBudget,
): Boolean {
    if (child in nestedModules) return true
    val name = child.fileName.toString()
    if (Files.isSymbolicLink(child)) return name in NODE_SCAN_IGNORED
    return when {
        Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> enqueueNodeDirectory(child, depth, queue)
        Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) -> safeNodeSource(child, budget)
        else -> false
    }
}

private fun enqueueNodeDirectory(
    directory: Path,
    depth: Int,
    queue: ArrayDeque<Pair<Path, Int>>,
): Boolean {
    val name = directory.fileName.toString()
    if (name in NODE_SCAN_IGNORED) return true
    if (name.startsWith('.')) return false
    if (depth >= MAX_NODE_DEPTH || queue.size >= MAX_NODE_DIRECTORIES) return false
    queue += directory to depth + 1
    return true
}

private fun safeNodeSource(source: Path, budget: NodeScanBudget): Boolean {
    val extension = source.extension.lowercase()
    if (extension in TRANSFORMED_SOURCE_EXTENSIONS) return false
    if (extension !in SCANNED_SOURCE_EXTENSIONS) return true
    if (budget.files++ >= MAX_NODE_SOURCE_FILES || !Files.isReadable(source)) return false
    val size = Files.size(source)
    budget.bytes += size
    if (size > MAX_NODE_FILE_BYTES || budget.bytes > MAX_NODE_TOTAL_BYTES) return false
    val text = runCatching { Files.readString(source, StandardCharsets.UTF_8) }.getOrNull() ?: return false
    return !hasDynamicNodeDependency(text)
}

private val SIMPLE_VERSION = Regex("""[~^]?(\d+)\.\d+(?:\.\d+)?""")
private val DEPENDENCY_FIELDS = listOf("dependencies", "devDependencies", "optionalDependencies", "peerDependencies")
private val RELATED_SOURCE_EXTENSIONS = setOf("js", "jsx", "ts", "tsx", "mjs", "cjs")
private val SCANNED_SOURCE_EXTENSIONS = RELATED_SOURCE_EXTENSIONS
private val TRANSFORMED_SOURCE_EXTENSIONS = setOf("vue", "svelte")
private val ROOT_FALLBACK_FILES = setOf(
    "package.json",
    "package-lock.json",
    "npm-shrinkwrap.json",
    "pnpm-lock.yaml",
    "pnpm-workspace.yaml",
    "yarn.lock",
)
private val PACKAGE_FALLBACK_FILES = ROOT_FALLBACK_FILES + setOf("tsconfig.json", "jsconfig.json")
private val NODE_CONFIG_FILES = setOf(
    ".babelrc",
    ".babelrc.js",
    ".babelrc.cjs",
    ".babelrc.mjs",
    ".babelrc.json",
    ".swcrc",
    "babel.config.js",
    "babel.config.cjs",
    "babel.config.mjs",
    "babel.config.cts",
    "babel.config.json",
    "jest.config.js",
    "jest.config.cjs",
    "jest.config.mjs",
    "jest.config.mts",
    "jest.config.cts",
    "jest.config.ts",
    "jest.config.json",
    "vite.config.js",
    "vite.config.cjs",
    "vite.config.mjs",
    "vite.config.mts",
    "vite.config.cts",
    "vite.config.ts",
    "vitest.config.js",
    "vitest.config.cjs",
    "vitest.config.mjs",
    "vitest.config.mts",
    "vitest.config.cts",
    "vitest.config.ts",
    "vitest.workspace.js",
    "vitest.workspace.cjs",
    "vitest.workspace.mjs",
    "vitest.workspace.mts",
    "vitest.workspace.cts",
    "vitest.workspace.ts",
    "vitest.workspace.json",
)
private val UNSAFE_PACKAGE_FIELDS = setOf("jest", "vitest", "babel", "overrides", "resolutions", "pnpm")
private val TRANSFORM_DEPENDENCIES = setOf("babel-jest", "ts-jest", "@swc/jest")
private val NODE_SCAN_IGNORED = setOf(
    "node_modules",
    "coverage",
    ".git",
    ".cache",
)
private val NODE_GENERATED_DIRECTORIES = setOf("build", "out", "dist", "target")
private val SUPPORTED_NODE_MANAGERS = setOf("npm", "yarn", "pnpm")
private val NODE_PNPM_MARKERS = setOf("pnpm-lock.yaml", "pnpm-workspace.yaml")
private val NODE_NPM_MARKERS = setOf("package-lock.json", "npm-shrinkwrap.json")
private val NODE_UNSUPPORTED_MANAGER_FILES = setOf("bun.lock", "bun.lockb", "deno.lock")
private val NODE_MANAGER_FILES = NODE_PNPM_MARKERS + NODE_NPM_MARKERS + NODE_UNSUPPORTED_MANAGER_FILES + "yarn.lock"
private const val MAX_NODE_DEPTH = 7
private const val MAX_NODE_DIRECTORIES = 4096
private const val MAX_NODE_SOURCE_FILES = 4096
private const val MAX_NODE_FILE_BYTES = 8L * 1024L * 1024L
private const val MAX_NODE_TOTAL_BYTES = 64L * 1024L * 1024L
