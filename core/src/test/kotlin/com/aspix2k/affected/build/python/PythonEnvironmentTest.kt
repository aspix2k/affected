package com.aspix2k.affected.build.python

import com.aspix2k.affected.build.PYTHON_RUNNER_DISCOVERY_FAILURE
import com.aspix2k.affected.build.unittestChanges
import com.aspix2k.affected.build.unittestModules
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class PythonEnvironmentTest {

    @Test
    fun `uv lock runs pytest selection through a locked uv environment`() {
        val fixture = environmentFixture("uv.lock", pyproject = PYTEST_PROJECT)

        assertEquals(UV_LAUNCHER, fixture.launcher())
        val command = fixture.pytestCommand()
        assertEquals(UV_LAUNCHER + listOf("python", fixture.adapter.toString()), command.take(UV_LAUNCHER.size + 2))
        assertEquals(plainPytestCommand(fixture), command.drop(UV_LAUNCHER.size))
    }

    @Test
    fun `poetry lock runs pytest selection through poetry run`() {
        val fixture = environmentFixture("poetry.lock", pyproject = PYTEST_PROJECT + POETRY_TABLE)

        assertEquals(POETRY_LAUNCHER, fixture.launcher())
        assertEquals(POETRY_LAUNCHER + plainPytestCommand(fixture), fixture.pytestCommand())
    }

    @Test
    fun `unittest exact and full commands run inside the managed environment`() {
        val fixture = environmentFixture("uv.lock", pyproject = "[project]\nname = \"app\"\n")
        val test = File(fixture.root, "packages/a/test_alpha.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
        }
        val adapter = File(fixture.root, "affected_unittest.py").apply { writeText("# adapter\n") }.toPath()
        val modules = unittestModules(fixture.root, "pkg-a", "packages/a")
        val toolchain = toolchain(fixture, PythonTestRunner.UNITTEST)

        val exact = resolvedPythonCommands(
            fixture.root.path,
            listOf("pkg-a:test"),
            modules,
            unittestChanges(test),
            adapter,
            toolchain = toolchain,
        ).single()
        val full = resolvedPythonCommands(
            fixture.root.path,
            listOf("pkg-a:test"),
            modules,
            null,
            null,
            toolchain = toolchain,
        ).single()
        val plainFull = resolvedPythonCommands(
            fixture.root.path,
            listOf("pkg-a:test"),
            modules,
            null,
            null,
            toolchain = PythonToolchain(PythonTestRunner.UNITTEST, emptyList()),
        ).single()

        assertEquals(UV_LAUNCHER + listOf("python", adapter.toString()), exact.arguments.take(UV_LAUNCHER.size + 2))
        assertEquals(UV_LAUNCHER + plainFull.arguments, full.arguments)
        assertEquals(listOf("python", "-m", "unittest", "discover", "-s", "packages/a", "-t", "."), plainFull.arguments)
    }

    @Test
    fun `mypy runs inside the managed environment and failure commands stay untouched`() {
        val fixture = environmentFixture("poetry.lock", pyproject = PYTEST_PROJECT)
        val modules = unittestModules(fixture.root, "pkg-a", "packages/a")

        val mypy = resolvedPythonCommands(
            fixture.root.path,
            listOf("pkg-a:typecheck"),
            modules,
            null,
            null,
            toolchain = toolchain(fixture, PythonTestRunner.PYTEST),
        ).single()
        assertEquals(POETRY_LAUNCHER + listOf("python", "-m", "mypy", "packages/a"), mypy.arguments)

        val unresolved = unresolvedRunnerArguments(fixture)
        assertEquals(listOf("python", "-c", PYTHON_RUNNER_DISCOVERY_FAILURE), unresolved)
    }

    @Test
    fun `missing tool binary keeps the plain interpreter`() {
        assertEquals(emptyList(), environmentFixture("uv.lock", binaries = emptyList()).launcher())
        assertEquals(emptyList(), environmentFixture("poetry.lock", binaries = listOf("uv")).launcher())
        assertEquals(emptyList(), environmentFixture("uv.lock", binaries = listOf("poetry")).launcher())
    }

    @Test
    fun `ambiguous uv and poetry markers keep the plain interpreter`() {
        val both = environmentFixture("uv.lock", binaries = listOf("uv", "poetry"))
        File(both.root, "poetry.lock").writeText("")
        assertEquals(emptyList(), both.launcher())

        val uvWithPoetryTable = environmentFixture("uv.lock", pyproject = PYTEST_PROJECT + POETRY_TABLE)
        assertEquals(emptyList(), uvWithPoetryTable.launcher())

        val poetryWithUvTable = environmentFixture("poetry.lock", pyproject = PYTEST_PROJECT + UV_TABLE)
        assertEquals(emptyList(), poetryWithUvTable.launcher())
    }

    @Test
    fun `tool tables without a lock and tox or nox projects keep the plain interpreter`() {
        assertEquals(emptyList(), environmentFixture(null, pyproject = PYTEST_PROJECT + UV_TABLE).launcher())
        assertEquals(emptyList(), environmentFixture(null, pyproject = PYTEST_PROJECT + POETRY_TABLE).launcher())

        val toxNox = environmentFixture(null, pyproject = PYTEST_PROJECT)
        File(toxNox.root, "tox.ini").writeText("[tox]\nenvlist = py\n")
        File(toxNox.root, "noxfile.py").writeText("import nox\n")
        assertEquals(emptyList(), toxNox.launcher())
        assertEquals(plainPytestCommand(toxNox), toxNox.pytestCommand())
    }

    @Test
    fun `symlinked or directory locks keep the plain interpreter`() {
        val linked = environmentFixture(null)
        val target = File(linked.root, "elsewhere.lock").apply { writeText("") }
        Files.createSymbolicLink(File(linked.root, "uv.lock").toPath(), target.toPath())
        assertEquals(emptyList(), linked.launcher())

        val directory = environmentFixture(null)
        File(directory.root, "poetry.lock").mkdirs()
        assertEquals(emptyList(), directory.launcher())
    }

    private inner class Fixture(val root: File, val bin: File, val adapter: Path) {
        fun launcher(): List<String> = pythonLauncher(root, bin.path, null)

        fun pytestCommand(): List<String> = pytestArguments(this, launcher())
    }

    private fun environmentFixture(
        lock: String?,
        pyproject: String = PYTEST_PROJECT,
        binaries: List<String> = listOf("uv", "poetry"),
    ): Fixture {
        val root = createTempDirectory("python-environment").toFile()
        File(root, "pyproject.toml").writeText(pyproject)
        lock?.let { File(root, it).writeText("") }
        val bin = createTempDirectory("python-environment-bin").toFile()
        binaries.forEach { name -> File(bin, name).apply { writeText("#!/bin/sh\n") }.setExecutable(true) }
        val adapter = File(root, "affected_pytest.py").apply { writeText("# adapter\n") }.toPath()
        return Fixture(root, bin, adapter)
    }

    private fun pytestArguments(fixture: Fixture, launcher: List<String>): List<String> {
        val test = File(fixture.root, "packages/a/test_alpha.py").apply {
            parentFile.mkdirs()
            writeText("def test_alpha():\n    pass\n")
        }
        return resolvedPythonCommands(
            fixture.root.path,
            listOf("pkg-a:test"),
            unittestModules(fixture.root, "pkg-a", "packages/a"),
            unittestChanges(test),
            fixture.adapter,
            toolchain = PythonToolchain(PythonTestRunner.PYTEST, launcher),
        ).single().arguments
    }

    private fun toolchain(fixture: Fixture, runner: PythonTestRunner) = PythonToolchain(runner, fixture.launcher())

    private fun plainPytestCommand(fixture: Fixture): List<String> = pytestArguments(fixture, emptyList())

    private fun unresolvedRunnerArguments(fixture: Fixture): List<String> {
        val outside = createTempDirectory("python-environment-outside").toFile()
        val external = File(outside, "test_external.py").apply { writeText("import unittest\n") }
        Files.createSymbolicLink(File(fixture.root, "test_linked.py").toPath(), external.toPath())
        try {
            return resolvedPythonCommands(
                fixture.root.path,
                listOf("pkg-a:test"),
                unittestModules(fixture.root, "pkg-a", "packages/a"),
                null,
                null,
                toolchain = PythonToolchain(pythonTestRunner(fixture.root), fixture.launcher()),
            ).single().arguments
        } finally {
            outside.deleteRecursively()
        }
    }

    private companion object {
        val UV_LAUNCHER = listOf("uv", "run", "--locked")
        val POETRY_LAUNCHER = listOf("poetry", "run")
        const val PYTEST_PROJECT = "[project]\nname = \"app\"\ndependencies = [\"pytest>=8\"]\n"
        const val UV_TABLE = "\n[tool.uv]\npackage = false\n"
        const val POETRY_TABLE = "\n[tool.poetry]\nname = \"app\"\n"
    }
}
