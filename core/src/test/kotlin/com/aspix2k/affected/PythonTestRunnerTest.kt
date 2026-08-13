package com.aspix2k.affected

import com.aspix2k.affected.build.PythonTestRunner
import com.aspix2k.affected.build.pythonTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class PythonTestRunnerTest {

    @Test
    fun `pytest in project metadata stays on pytest`() {
        val root = project()
        pyproject(root, ".", """
            [project]
            name = "app"
            dependencies = ["pytest>=8.0"]
        """.trimIndent())
        testModule(root, "tests/test_app.py", "import unittest\n")

        assertEquals(PythonTestRunner.PYTEST, pythonTestRunner(root))
    }

    @Test
    fun `tool pytest section stays on pytest`() {
        val root = project()
        pyproject(root, ".", """
            [project]
            name = "app"

            [tool.pytest.ini_options]
            testpaths = ["tests"]
        """.trimIndent())

        assertEquals(PythonTestRunner.PYTEST, pythonTestRunner(root))
    }

    @Test
    fun `pytest ini stays on pytest`() {
        val root = project()
        pyproject(root, ".", "[project]\nname = \"app\"\n")
        File(root, "pytest.ini").writeText("[pytest]\n")

        assertEquals(PythonTestRunner.PYTEST, pythonTestRunner(root))
    }

    @Test
    fun `unittest tests without pytest use unittest`() {
        val root = project()
        pyproject(root, ".", "[project]\nname = \"app\"\n")
        testModule(root, "tests/test_app.py", "import unittest\n\nclass AppTest(unittest.TestCase):\n    pass\n")

        assertEquals(PythonTestRunner.UNITTEST, pythonTestRunner(root))
    }

    @Test
    fun `from unittest import is enough`() {
        val root = project()
        pyproject(root, ".", "[project]\nname = \"app\"\n")
        testModule(root, "pkg/test_util.py", "from unittest import TestCase\n")

        assertEquals(PythonTestRunner.UNITTEST, pythonTestRunner(root))
    }

    @Test
    fun `no pytest and no unittest import keeps pytest`() {
        val root = project()
        pyproject(root, ".", "[project]\nname = \"app\"\n")
        testModule(root, "tests/test_app.py", "def test_ok():\n    assert True\n")

        assertEquals(PythonTestRunner.PYTEST, pythonTestRunner(root))
    }

    @Test
    fun `venv copies do not count as unittest`() {
        val root = project()
        pyproject(root, ".", "[project]\nname = \"app\"\n")
        testModule(root, ".venv/lib/test_hidden.py", "import unittest\n")

        assertEquals(PythonTestRunner.PYTEST, pythonTestRunner(root))
    }

    private fun project(): File = createTempDirectory("python-runner").toFile()

    private fun pyproject(root: File, path: String, body: String) {
        val directory = if (path == ".") root else File(root, path).apply { mkdirs() }
        File(directory, "pyproject.toml").writeText(body)
    }

    private fun testModule(root: File, relative: String, body: String) {
        File(root, relative).apply { parentFile.mkdirs(); writeText(body) }
    }
}
