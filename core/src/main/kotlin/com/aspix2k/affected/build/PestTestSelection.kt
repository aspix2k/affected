package com.aspix2k.affected.build

import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun selectPestTestFiles(
    root: String,
    suitePaths: List<String>,
    changes: BuildChanges,
): List<String>? = runCatching {
    require(changes.comparedToBase)
    require(changes.files.isNotEmpty())
    require(changes.files.toSet() == changes.exactSelectionEligible)
    require(changes.files.size <= MAX_PEST_FILTER_FILES)
    require(suitePaths.isNotEmpty() && suitePaths.size <= MAX_PEST_FILTER_FILES)

    val rootPath = File(root).toPath().toAbsolutePath().normalize()
    require(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(rootPath))
    val realRoot = rootPath.toRealPath(LinkOption.NOFOLLOW_LINKS)
    val suites = suitePaths.map { suite -> realSuite(rootPath, realRoot, suite) }

    val selected = LinkedHashSet<String>()
    val datasetNames = LinkedHashSet<String>()
    val productionClasses = LinkedHashSet<String>()
    for (raw in changes.files) {
        val requested = Path.of(raw).toAbsolutePath().normalize()
        require(Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(requested))
        val real = requested.toRealPath(LinkOption.NOFOLLOW_LINKS)
        val suite = suites.singleOrNull { real.startsWith(it) }
        val relative = pestRelative(realRoot, real)
        when {
            suite != null && isPestExactTestFile(suite, real) -> selected += relative
            suite != null && isPestDatasetFile(suite, real) -> {
                datasetNames += pestDatasetNames(real)
                selected += relative
            }
            suite == null -> productionClasses += pestPsr4Class(realRoot, real) ?: return@runCatching null
            else -> return@runCatching null
        }
    }
    if (datasetNames.isNotEmpty()) {
        val consumers = pestDatasetConsumers(realRoot, suites, datasetNames)
        require(consumers.isNotEmpty())
        selected += consumers
    }
    if (productionClasses.isNotEmpty()) {
        val consumers = pestClassConsumers(realRoot, suites, productionClasses)
        require(consumers.isNotEmpty())
        selected += consumers
    }
    require(selected.isNotEmpty())
    require(suites.all { suite -> selected.any { coversSuite(realRoot, it, suite) } })
    selected.sorted()
}.getOrNull()

private fun realSuite(root: Path, realRoot: Path, relative: String): Path {
    val requested = root.resolve(relative.removePrefix("./")).normalize()
    require(requested.startsWith(root))
    require(Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(requested))
    val real = requested.toRealPath(LinkOption.NOFOLLOW_LINKS)
    require(real.startsWith(realRoot))
    return real
}

private fun pestRelative(root: Path, file: Path): String {
    val relative = root.relativize(file).toString().replace('\\', '/')
    require(relative.isNotEmpty() && !relative.startsWith("../"))
    return "./$relative"
}

private fun isPestDatasetFile(suite: Path, file: Path): Boolean {
    val name = file.fileName.toString()
    if (!name.endsWith(".php", ignoreCase = true)) return false
    if (name == "Datasets.php") return true
    return suite.relativize(file).joinToString("/").split('/').dropLast(1).contains("Datasets")
}

private fun coversSuite(root: Path, relative: String, suite: Path): Boolean =
    root.resolve(relative.removePrefix("./")).normalize().startsWith(suite)

private fun pestDatasetUses(file: Path, names: Set<String>): Set<String>? {
    val text = readPestPhp(file) ?: return null
    val used = WITH_DATASET.findAll(text).map { it.groupValues[1] }.filterTo(HashSet(), names::contains)
    return used.takeIf { it.isNotEmpty() }
}

private fun pestPsr4Class(root: Path, file: Path): String? {
    if (!file.fileName.toString().endsWith(".php", ignoreCase = true)) return null
    val manifest = pestComposerManifest(root, file) ?: return null
    val prefixes = pestPsr4Prefixes(manifest) ?: return null
    val packageRoot = manifest.parent
    val relative = packageRoot.relativize(file).toString().replace('\\', '/')
    require(relative.endsWith(".php", ignoreCase = true) && !relative.startsWith("../"))
    val withoutExtension = relative.removeSuffix(".php").removeSuffix(".PHP")
    for ((prefix, directory) in prefixes) {
        val dir = directory.trimEnd('/') + "/"
        if (!withoutExtension.startsWith(dir) && withoutExtension != directory.trimEnd('/')) continue
        val suffix = withoutExtension.removePrefix(directory.trimEnd('/')).trimStart('/')
        return prefix.trimEnd('\\') + (if (suffix.isEmpty()) "" else "\\" + suffix.replace('/', '\\'))
    }
    return null
}

private fun pestComposerManifest(root: Path, file: Path): Path? {
    var directory = file.parent ?: return null
    while (directory.startsWith(root)) {
        val candidate = directory.resolve("composer.json")
        if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(candidate)) {
            return candidate
        }
        directory = directory.parent ?: break
    }
    return null
}

private fun pestPsr4Prefixes(manifest: Path): List<Pair<String, String>>? {
    val json = JsonParser.parseString(readPestPhp(manifest) ?: return null).asJsonObject
    val prefixes = ArrayList<Pair<String, String>>()
    for (section in listOf("autoload", "autoload-dev")) {
        val psr4 = json.get(section)?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("psr-4")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
        for ((prefix, value) in psr4.entrySet()) {
            val directory = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
            prefixes += prefix.replace('/', '\\') to directory.replace('\\', '/').trimStart('/')
        }
    }
    return prefixes.takeIf { it.isNotEmpty() }
}

private fun pestClassConsumers(root: Path, suites: List<Path>, classes: Set<String>): Set<String> {
    require(classes.isNotEmpty())
    val consumers = LinkedHashSet<String>()
    val remaining = classes.toHashSet()
    for (suite in suites) {
        val files = ArrayList<Path>()
        collectPestTestFiles(suite, suite, files)
        require(files.size <= MAX_PEST_FILTER_FILES)
        for (file in files) {
            val used = pestImportedClasses(file, classes) ?: continue
            consumers += pestRelative(root, file)
            remaining.removeAll(used)
        }
    }
    require(remaining.isEmpty())
    return consumers
}

private fun pestImportedClasses(file: Path, classes: Set<String>): Set<String>? {
    val text = readPestPhp(file) ?: return null
    val used = LinkedHashSet<String>()
    for (name in USE_CLASS.findAll(text).map { it.groupValues[1] }) {
        if (name in classes) used += name
    }
    for (name in classes) {
        if (text.contains("\\$name")) used += name
    }
    return used.takeIf { it.isNotEmpty() }
}

private fun pestDatasetNames(file: Path): Set<String> {
    val text = readPestPhp(file) ?: return emptySet()
    return DATASET_DECLARATION.findAll(text).mapTo(LinkedHashSet()) { it.groupValues[1] }
}

private fun pestDatasetConsumers(root: Path, suites: List<Path>, names: Set<String>): Set<String> {
    require(names.isNotEmpty())
    val consumers = LinkedHashSet<String>()
    val remaining = names.toHashSet()
    for (suite in suites) {
        val files = ArrayList<Path>()
        collectPestTestFiles(suite, suite, files)
        require(files.size <= MAX_PEST_FILTER_FILES)
        for (file in files) {
            val used = pestDatasetUses(file, names) ?: continue
            consumers += pestRelative(root, file)
            remaining.removeAll(used)
        }
    }
    require(remaining.isEmpty())
    return consumers
}

private fun collectPestTestFiles(suite: Path, directory: Path, found: MutableList<Path>) {
    val children = directory.toFile().listFiles() ?: return
    require(children.size <= MAX_PEST_FILTER_FILES)
    for (child in children) {
        val path = child.toPath()
        require(!Files.isSymbolicLink(path))
        when {
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> collectPestTestFiles(suite, path, found)
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && isPestExactTestFile(suite, path) -> {
                require(found.size < MAX_PEST_FILTER_FILES)
                found.add(path)
            }
        }
    }
}

private fun readPestPhp(file: Path): String? = runCatching {
    require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file))
    require(Files.size(file) <= MAX_PEST_PHP_BYTES)
    Files.readString(file)
}.getOrNull()

private fun isPestExactTestFile(suite: Path, file: Path): Boolean {
    val name = file.fileName.toString()
    if (name in PEST_NON_EXACT_FILES) return false
    val relative = suite.relativize(file).joinToString("/")
    if (relative.split('/').dropLast(1).any { it in PEST_NON_EXACT_DIRECTORIES }) return false
    return name.endsWith(".php", ignoreCase = true) || name.endsWith(".phpt", ignoreCase = true)
}

private val PEST_NON_EXACT_FILES = setOf(
    "Pest.php",
    "Datasets.php",
    "Expectations.php",
    "Helpers.php",
)

private val PEST_NON_EXACT_DIRECTORIES = setOf("Datasets", "Expectations", "Helpers")

private val DATASET_DECLARATION = Regex("""dataset\s*\(\s*['\"]([^'\"]+)['\"]""")
private val WITH_DATASET = Regex("""->\s*with\s*\(\s*['\"]([^'\"]+)['\"]""")
private val USE_CLASS = Regex("""(?:^|[\r\n])\s*use\s+([A-Za-z_][A-Za-z0-9_\\]*)""")

private const val MAX_PEST_FILTER_FILES = 256
private const val MAX_PEST_PHP_BYTES = 1024 * 1024
