package com.aspix2k.affected.build.python

import com.aspix2k.affected.build.PYTHON_RUNNER_DISCOVERY_FAILURE
import com.aspix2k.affected.build.process.CliCommand
import com.aspix2k.affected.build.unittestChanges
import com.aspix2k.affected.build.unittestModules
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PythonEnvironmentTest {

    @Test
    fun `the plain interpreter prefers the project virtual environment, then python, then python3`() {
        val root = createTempDirectory("python-interpreter").toFile()
        val tools = createTempDirectory("python-tools").toFile()
        val only3 = File(tools, "python3").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        val pytest = CliCommand("pytest", listOf("python", "-m", "pytest", "."))
        val managed = CliCommand("pytest", listOf("/opt/bin/uv", "run", "--locked", "python", "-m", "pytest"))

        assertEquals("python", pythonInterpreter(root, path = "", pathExt = null))
        assertEquals("python3", pythonInterpreter(root, path = tools.path, pathExt = null))
        File(tools, "python").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        assertEquals("python", pythonInterpreter(root, path = tools.path, pathExt = null))
        val virtual = File(root, ".venv/bin/python").apply {
            parentFile.mkdirs()
            only3.copyTo(this)
            setExecutable(true)
        }
        assertEquals(virtual.absolutePath, pythonInterpreter(root, path = tools.path, pathExt = null))
        assertEquals(
            listOf(virtual.absolutePath, "-m", "pytest", "."),
            pytest.withPythonInterpreter(virtual.absolutePath).arguments,
        )
        assertEquals(managed, managed.withPythonInterpreter(virtual.absolutePath))
    }

    @Test
    fun `a local Python SDK configured in the IDE wins over the virtual environment and PATH`() {
        val root = createTempDirectory("python-sdk").toFile()
        val sdk = File(root, "sdk/bin/python").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
        File(root, ".venv/bin/python").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }

        assertEquals(sdk.path, configuredPythonInterpreter("Python SDK", sdk.path))
        assertEquals(null, configuredPythonInterpreter("JavaSDK", sdk.path))
        assertEquals(null, configuredPythonInterpreter("Python SDK", "ssh://user@host/usr/bin/python"))
        assertEquals(null, configuredPythonInterpreter("Python SDK", null))
        assertEquals(
            sdk.path,
            pythonInterpreter(root, configuredPythonInterpreter("Python SDK", sdk.path), path = "", pathExt = null),
        )
    }

    @Test
    fun `uv lock runs pytest selection through a locked uv environment`() {
        val fixture = environmentFixture("uv.lock", pyproject = PYTEST_PROJECT)

        assertEquals(UV_LAUNCHER, fixture.launcher().arguments)
        val command = fixture.pytestCommand()
        assertEquals(UV_LAUNCHER + listOf("python", fixture.adapter.toString()), command.take(UV_LAUNCHER.size + 2))
        assertEquals(plainPytestCommand(fixture), command.drop(UV_LAUNCHER.size))
    }

    @Test
    fun `poetry lock runs pytest selection through poetry run`() {
        val fixture = environmentFixture("poetry.lock", pyproject = PYTEST_PROJECT + POETRY_TABLE)

        assertEquals(POETRY_LAUNCHER, fixture.launcher().arguments)
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
            toolchain = PythonToolchain(PythonTestRunner.UNITTEST),
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
    fun `a lock without its tool fails visibly instead of using the plain interpreter`() {
        val missing = environmentFixture("uv.lock", binaries = emptyList())
        assertEquals(emptyList(), missing.launcher().arguments)
        assertContains(checkNotNull(missing.launcher().failure), "uv is not on PATH")
        assertEquals(failureArguments(missing.launcher()), missing.pytestCommand())
        val poetryOnly = environmentFixture("poetry.lock", binaries = listOf("uv"))
        assertContains(checkNotNull(poetryOnly.launcher().failure), "poetry")
        val uvOnly = environmentFixture("uv.lock", binaries = listOf("poetry"))
        assertContains(checkNotNull(uvOnly.launcher().failure), "uv")
    }

    @Test
    fun `a tool in a common install directory is used by its absolute path`() {
        val fixture = environmentFixture("uv.lock", binaries = emptyList())
        val directory = createTempDirectory("python-environment-tools").toFile()
        val uv = File(directory, "uv").apply { writeText("#!/bin/sh\n") }.also { it.setExecutable(true) }

        val launcher = fixture.launcher(listOf(directory.path))

        assertEquals(null, launcher.failure)
        assertEquals(listOf(uv.absoluteFile.normalize().invariantSeparatorsPath, "run", "--locked"), launcher.arguments)
    }

    @Test
    fun `ambiguous uv and poetry markers fail visibly`() {
        val both = environmentFixture("uv.lock", binaries = listOf("uv", "poetry"))
        File(both.root, "poetry.lock").writeText("")
        assertContains(checkNotNull(both.launcher().failure), "both uv.lock and poetry.lock")
        assertEquals(failureArguments(both.launcher()), both.pytestCommand())

        val uvWithPoetryTable = environmentFixture("uv.lock", pyproject = PYTEST_PROJECT + POETRY_TABLE)
        assertContains(checkNotNull(uvWithPoetryTable.launcher().failure), "conflicts")

        val poetryWithUvTable = environmentFixture("poetry.lock", pyproject = PYTEST_PROJECT + UV_TABLE)
        assertContains(checkNotNull(poetryWithUvTable.launcher().failure), "conflicts")
    }

    @Test
    fun `a uv workspace member uses the workspace lock found above it`() {
        val workspace = workspaceFixture(members = "\"packages/*\"")

        assertEquals(UV_LAUNCHER, workspace.launcher().arguments)
        assertEquals(null, workspace.launcher().failure)
    }

    @Test
    fun `a uv workspace lock does not apply to excluded or unlisted directories`() {
        val excluded = workspaceFixture(members = "\"packages/*\"", exclude = "\"packages/a\"")
        assertEquals(PythonLauncher(), excluded.launcher())
        assertEquals(PythonLauncher(), workspaceFixture(members = "\"libs/*\"").launcher())
        assertEquals(PythonLauncher(), workspaceFixture(members = null).launcher())
    }

    @Test
    fun `ancestor locks stop at the repository boundary and never apply to poetry`() {
        val outside = workspaceFixture(members = "\"packages/*\"", repositoryAtMember = true)
        assertEquals(PythonLauncher(), outside.launcher())

        val poetry = workspaceFixture(members = "\"packages/*\"", lock = "poetry.lock")
        assertEquals(PythonLauncher(), poetry.launcher())
    }

    @Test
    fun `tool tables without a lock and tox or nox projects keep the plain interpreter`() {
        assertEquals(PythonLauncher(), environmentFixture(null, pyproject = PYTEST_PROJECT + UV_TABLE).launcher())
        assertEquals(PythonLauncher(), environmentFixture(null, pyproject = PYTEST_PROJECT + POETRY_TABLE).launcher())

        val toxNox = environmentFixture(null, pyproject = PYTEST_PROJECT)
        File(toxNox.root, "tox.ini").writeText("[tox]\nenvlist = py\n")
        File(toxNox.root, "noxfile.py").writeText("import nox\n")
        assertEquals(PythonLauncher(), toxNox.launcher())
        assertEquals(plainPytestCommand(toxNox), toxNox.pytestCommand())
    }

    @Test
    fun `symlinked or directory locks keep the plain interpreter`() {
        val linked = environmentFixture(null)
        val target = File(linked.root, "elsewhere.lock").apply { writeText("") }
        Files.createSymbolicLink(File(linked.root, "uv.lock").toPath(), target.toPath())
        assertEquals(PythonLauncher(), linked.launcher())

        val directory = environmentFixture(null)
        File(directory.root, "poetry.lock").mkdirs()
        assertEquals(PythonLauncher(), directory.launcher())
    }

    private inner class Fixture(val root: File, val bin: File, val adapter: Path) {
        fun launcher(toolDirectories: List<String> = emptyList()): PythonLauncher =
            pythonLauncher(root, bin.path, null, toolDirectories)

        fun pytestCommand(): List<String> = pytestArguments(this, launcher())
    }

    private fun workspaceFixture(
        members: String?,
        exclude: String? = null,
        lock: String = "uv.lock",
        repositoryAtMember: Boolean = false,
    ): Fixture {
        val workspace = createTempDirectory("python-workspace").toFile()
        val table = members?.let {
            "\n[tool.uv.workspace]\nmembers = [$it]\n" + exclude?.let { value -> "exclude = [$value]\n" }.orEmpty()
        }.orEmpty()
        File(workspace, "pyproject.toml").writeText("[project]\nname = \"root\"\n$table")
        File(workspace, lock).writeText("")
        val member = File(workspace, "packages/a").apply { mkdirs() }
        File(member, "pyproject.toml").writeText(PYTEST_PROJECT)
        File(if (repositoryAtMember) member else workspace, ".git").mkdirs()
        val bin = createTempDirectory("python-environment-bin").toFile()
        listOf("uv", "poetry").forEach { name ->
            File(bin, name).apply { writeText("#!/bin/sh\n") }.setExecutable(true)
        }
        val adapter = File(member, "affected_pytest.py").apply { writeText("# adapter\n") }.toPath()
        return Fixture(member, bin, adapter)
    }

    private fun failureArguments(launcher: PythonLauncher): List<String> =
        checkNotNull(launcher.failureCommand()).arguments

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

    private fun pytestArguments(fixture: Fixture, launcher: PythonLauncher): List<String> {
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

    private fun plainPytestCommand(fixture: Fixture): List<String> = pytestArguments(fixture, PythonLauncher())

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
