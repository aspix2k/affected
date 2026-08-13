package com.aspix2k.affected.build

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
    val hit = HashSet<Path>()
    for (raw in changes.files) {
        val requested = Path.of(raw).toAbsolutePath().normalize()
        require(Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(requested))
        val real = requested.toRealPath(LinkOption.NOFOLLOW_LINKS)
        val suite = suites.singleOrNull { real.startsWith(it) } ?: return@runCatching null
        require(isPestExactTestFile(suite, real))
        val relative = realRoot.relativize(real).toString().replace('\\', '/')
        require(relative.isNotEmpty() && !relative.startsWith("../"))
        selected += "./$relative"
        hit.add(suite)
    }
    require(selected.isNotEmpty() && hit.containsAll(suites))
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

private const val MAX_PEST_FILTER_FILES = 256
