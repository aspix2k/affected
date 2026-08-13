package com.aspix2k.affected.build

import java.io.File

internal enum class PythonTestRunner {
    PYTEST,
    UNITTEST,
}

internal fun pythonTestRunner(root: File): PythonTestRunner {
    if (pytestDeclared(root)) return PythonTestRunner.PYTEST
    return if (unittestUsed(root)) PythonTestRunner.UNITTEST else PythonTestRunner.PYTEST
}

private fun pytestDeclared(root: File): Boolean {
    if (File(root, "pytest.ini").isFile) return true
    if (ManifestSearch.anyFile(root) { it.name == "conftest.py" } == true) return true
    return ManifestSearch.find(root, "pyproject.toml").any(::pyprojectDeclaresPytest)
}

private fun pyprojectDeclaresPytest(manifest: File): Boolean {
    val text = ManifestSearch.readText(manifest) ?: return false
    if (text.contains("[tool.pytest")) return true
    return PYTEST_DEPENDENCY.containsMatchIn(text)
}

private fun unittestUsed(root: File): Boolean =
    ManifestSearch.anyFile(root) { file ->
        isPythonTestModule(file) && unittestImported(file)
    } == true

internal fun isPythonTestModule(file: File): Boolean {
    if (file.extension != "py") return false
    return file.name.startsWith("test_") || file.name.endsWith("_test.py")
}

private fun unittestImported(file: File): Boolean {
    val text = ManifestSearch.readText(file) ?: return false
    return UNITTEST_IMPORT.containsMatchIn(text)
}

private val PYTEST_DEPENDENCY = Regex("""["']pytest(?:-[A-Za-z0-9._]+)?(?:["']|[<>=!~\s])""")
private val UNITTEST_IMPORT = Regex("""(?:^|\n)\s*(?:import unittest\b|from unittest\b)""")
