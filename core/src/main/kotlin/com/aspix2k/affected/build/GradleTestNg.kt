package com.aspix2k.affected.build

import com.aspix2k.affected.ChangeAnalyzer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun gradleTaskNames(tasks: List<String>, changes: BuildChanges?): List<String> {
    val classes = changes?.let(::selectTestNgClasses) ?: return tasks
    return tasks + classes.flatMap { listOf("--tests", it) }
}

internal fun selectTestNgClasses(changes: BuildChanges): List<String>? = runCatching {
    require(changes.comparedToBase)
    require(changes.files.isNotEmpty() && changes.files.size <= MAX_TESTNG_CLASSES)
    require(changes.files.toSet() == changes.exactSelectionEligible)
    val names = LinkedHashSet<String>()
    for (raw in changes.files) {
        val requested = Path.of(raw).toAbsolutePath().normalize()
        require(Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(requested))
        val real = requested.toRealPath(LinkOption.NOFOLLOW_LINKS)
        val fileName = real.fileName.toString()
        require(
            fileName.endsWith(".java") || fileName.endsWith(".kt") || fileName.endsWith(".groovy"),
        )
        require(ChangeAnalyzer.isTestSource("GRADLE", real.toString().replace('\\', '/')))
        val text = Files.readString(real)
        names += requireNotNull(gradleExactTestClass(fileName, text))
    }
    names.sorted().takeIf { it.isNotEmpty() }
}.getOrNull()

private fun gradleExactTestClass(fileName: String, text: String): String? = when {
    "org.testng" in text -> testNgClassName(fileName, text)
    "spock.lang" in text || text.contains("extends Specification") -> testNgClassName(fileName, text)
    else -> null
}

private fun testNgClassName(fileName: String, text: String): String {
    val simple = TESTNG_CLASS.find(text)?.groupValues?.get(1)
    require(!simple.isNullOrEmpty())
    require(fileName.substringBefore('.') == simple)
    val pkg = TESTNG_PACKAGE.find(text)?.groupValues?.get(1)
    return if (pkg.isNullOrEmpty()) simple else "$pkg.$simple"
}

private val TESTNG_PACKAGE = Regex("""(?m)^\s*package\s+([\w.]+)\s*;?\s*$""")
private val TESTNG_CLASS = Regex(
    """(?m)^\s*(?:public\s+|internal\s+|open\s+|abstract\s+|final\s+)*class\s+(\w+)""",
)
private const val MAX_TESTNG_CLASSES = 32
