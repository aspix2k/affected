package com.aspix2k.affected.build

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

internal enum class PythonTestRunner {
    PYTEST,
    UNITTEST,
    UNKNOWN,
}

internal fun pythonTestRunner(
    root: File,
    deadlineNanos: Long = System.nanoTime() + PerformanceBudgets.SCAN_TIME_NS,
): PythonTestRunner {
    val remaining = deadlineNanos - System.nanoTime()
    if (remaining <= 0) return PythonTestRunner.UNKNOWN
    var contents = emptyList<Pair<File, String>>()
    val files = ManifestSearch.completeFiles(
        root,
        remaining,
        afterScan = { scanned ->
            runnerFileContents(scanned, deadlineNanos)?.let {
                contents = it
                true
            } ?: false
        },
    ) { file ->
        file.name == "pytest.ini" ||
            file.name == "conftest.py" ||
            file.name == "pyproject.toml" ||
            isPythonTestModule(file)
    } ?: return PythonTestRunner.UNKNOWN
    if (files.size != contents.size) return PythonTestRunner.UNKNOWN
    return classifyPythonRunner(contents)
}

private fun classifyPythonRunner(contents: List<Pair<File, String>>): PythonTestRunner {
    var unittest = false
    for ((file, text) in contents) {
        if (file.name == "pytest.ini") {
            return PythonTestRunner.PYTEST
        }
        if (file.name == "conftest.py" || file.name == "pyproject.toml" && pyprojectDeclaresPytest(text)) {
            return PythonTestRunner.PYTEST
        }
        if (isPythonTestModule(file) && UNITTEST_IMPORT.containsMatchIn(text)) {
            unittest = true
        }
    }
    return if (unittest) PythonTestRunner.UNITTEST else PythonTestRunner.PYTEST
}

private fun runnerFileContents(files: List<File>, deadlineNanos: Long): List<Pair<File, String>>? {
    var totalBytes = 0L
    val result = ArrayList<Pair<File, String>>(files.size)
    for (file in files) {
        if (System.nanoTime() >= deadlineNanos) return null
        val (text, size) = readRunnerFile(file, PerformanceBudgets.MAX_TOTAL_BYTES - totalBytes) ?: return null
        totalBytes += size
        result += file to text
    }
    if (System.nanoTime() >= deadlineNanos) return null
    return result
}

private fun readRunnerFile(file: File, remainingBytes: Long): Pair<String, Long>? = runCatching {
    val path = file.toPath().toAbsolutePath().normalize()
    val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    require(before.isRegularFile && !Files.isSymbolicLink(path) && Files.isReadable(path))
    require(before.size() <= PerformanceBudgets.MAX_MANIFEST_BYTES && before.size() <= remainingBytes)
    val byteLimit = minOf(PerformanceBudgets.MAX_MANIFEST_BYTES, remainingBytes).toInt()
    val bytes = Files.newInputStream(path).use { it.readNBytes(byteLimit + 1) }
    require(bytes.size <= byteLimit)
    val text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    require(before.fileKey() == after.fileKey())
    require(before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime())
    require(text.toByteArray(StandardCharsets.UTF_8).size.toLong() == after.size())
    text to after.size()
}.getOrNull()

private fun pyprojectDeclaresPytest(text: String): Boolean {
    if (text.contains("[tool.pytest")) return true
    return PYTEST_DEPENDENCY.containsMatchIn(text)
}

internal fun isPythonTestModule(file: File): Boolean {
    if (file.extension != "py") return false
    return file.name.startsWith("test_") || file.name.endsWith("_test.py")
}

private val PYTEST_DEPENDENCY = Regex("""["']pytest(?:-[A-Za-z0-9._]+)?(?:["']|[<>=!~\s])""")
private val UNITTEST_IMPORT = Regex("""(?:^|\n)\s*(?:import unittest\b|from unittest\b)""")
