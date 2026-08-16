package com.aspix2k.affected.build

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PythonTestRunnerTest {

    @Test
    fun `incomplete Python runner discovery produces a visible failure`() {
        val root = createTempDirectory("python-runner-incomplete").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        val outside = createTempDirectory("python-runner-outside").toFile()
        val external = File(outside, "test_external.py").apply { writeText("import unittest\n") }
        val linked = File(root, "test_linked.py")
        Files.createSymbolicLink(linked.toPath(), external.toPath())
        val adapter = File(root, "affected_unittest.py").apply { writeText("# adapter\n") }.toPath()

        try {
            assertEquals(PythonTestRunner.UNKNOWN, pythonTestRunner(root))
            val arguments = pythonCommands(
                root.path,
                listOf("root:test"),
                modules(root),
                BuildChanges(
                    files = listOf(linked.path),
                    exactSelectionEligible = emptySet(),
                    comparedToBase = true,
                ),
                adapter,
            ).single().arguments

            assertEquals(listOf("python", "-c", PYTHON_RUNNER_DISCOVERY_FAILURE), arguments)
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `runner discovery scans past an early unittest match before trusting it`() {
        val root = createTempDirectory("python-runner-late-link").toFile()
        File(root, "first").mkdirs()
        File(root, "second").mkdirs()
        val ordered = root.listFiles().orEmpty().filter(File::isDirectory)
        val early = ordered.first()
        val late = ordered.last()
        File(early, "test_alpha.py").writeText("import unittest\n")
        val outside = createTempDirectory("python-runner-late-link-outside").toFile()
        val external = File(outside, "test_external.py").apply { writeText("import unittest\n") }
        Files.createSymbolicLink(File(late, "test_zeta.py").toPath(), external.toPath())

        try {
            assertEquals(PythonTestRunner.UNKNOWN, pythonTestRunner(root))
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `runner discovery fails closed at its bounded depth`() {
        val root = createTempDirectory("python-runner-depth").toFile()
        File(root, "test_alpha.py").writeText("import unittest\n")
        var nested = root
        repeat(9) { depth -> nested = File(nested, "level-$depth").apply { mkdir() } }
        File(nested, "conftest.py").writeText("def pytest_configure():\n    pass\n")

        assertEquals(PythonTestRunner.UNKNOWN, pythonTestRunner(root))
    }

    @Test
    fun `runner discovery rejects a directory link at its depth boundary`() {
        val root = createTempDirectory("python-runner-depth-link").toFile()
        File(root, "test_alpha.py").writeText("import unittest\n")
        var nested = root
        repeat(PerformanceBudgets.MAX_DEPTH) { depth ->
            nested = File(nested, "level-$depth").apply { mkdir() }
        }
        val outside = createTempDirectory("python-runner-depth-link-outside").toFile()
        File(outside, "pytest.ini").writeText("[pytest]\n")
        Files.createSymbolicLink(File(nested, "escaped").toPath(), outside.toPath())

        try {
            assertEquals(PythonTestRunner.UNKNOWN, pythonTestRunner(root))
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `complete runner discovery rejects a queued directory swapped to a link`() {
        val root = createTempDirectory("python-runner-queued-link").toFile()
        val nested = File(root, "nested").apply { mkdir() }
        File(nested, "trigger.py").writeText("import unittest\n")
        val outside = createTempDirectory("python-runner-queued-link-outside").toFile()
        File(outside, "pytest.ini").writeText("[pytest]\n")

        try {
            val result = ManifestSearch.completeFiles(root) { file ->
                if (file.name == "trigger.py") {
                    file.delete()
                    nested.delete()
                    Files.createSymbolicLink(nested.toPath(), outside.toPath())
                }
                file.extension == "py"
            }

            assertNull(result)
        } finally {
            if (Files.isSymbolicLink(nested.toPath())) Files.delete(nested.toPath())
            outside.deleteRecursively()
        }
    }

    @Test
    fun `complete runner discovery rejects changes in an already scanned directory`() {
        val root = createTempDirectory("python-runner-rescan").toFile()
        File(root, "first").mkdir()
        File(root, "second").mkdir()
        val directories = root.listFiles().orEmpty().filter(File::isDirectory)
        val scannedFirst = directories.first()
        val scannedLater = directories.last()
        File(scannedFirst, "placeholder.txt").writeText("stable\n")
        File(scannedLater, "trigger.py").writeText("import unittest\n")

        val result = ManifestSearch.completeFiles(root) { file ->
            if (file.name == "trigger.py") File(scannedFirst, "pytest.ini").writeText("[pytest]\n")
            file.extension == "py"
        }

        assertNull(result)
    }

    @Test
    fun `complete runner discovery rejects same size metadata rewrites after reading`() {
        val root = createTempDirectory("python-runner-file-rewrite").toFile()
        val metadata = File(root, "test_alpha.py")
        val before = "import unittest\n"
        val after = "import pytest  \n"
        assertEquals(before.length, after.length)
        metadata.writeText(before)

        val result = ManifestSearch.completeFiles(
            root,
            afterScan = {
                metadata.writeText(after)
                true
            },
        ) { it.name == metadata.name }

        assertNull(result)
    }

    @Test
    fun `nested pytest configuration wins over a shallow unittest module`() {
        val root = createTempDirectory("python-runner-nested-pytest").toFile()
        File(root, "test_alpha.py").writeText("import unittest\n")
        File(root, "packages/web/pytest.ini").apply {
            parentFile.mkdirs()
            writeText("[pytest]\n")
        }

        assertEquals(PythonTestRunner.PYTEST, pythonTestRunner(root))
    }

    @Test
    fun `runner discovery fails closed when relevant metadata exceeds its aggregate budget`() {
        val root = createTempDirectory("python-runner-total-bytes").toFile()
        File(root, "test_alpha.py").writeText("import unittest\n")
        repeat(9) { index ->
            val manifest = File(root, "package-$index/pyproject.toml").apply { parentFile.mkdirs() }
            RandomAccessFile(manifest, "rw").use { it.setLength(8L * 1024L * 1024L) }
        }

        assertEquals(PythonTestRunner.UNKNOWN, pythonTestRunner(root))
    }

    @Test
    fun `runner discovery fails closed after its shared deadline`() {
        val root = createTempDirectory("python-runner-deadline").toFile()
        File(root, "test_alpha.py").writeText("import unittest\n")

        assertEquals(PythonTestRunner.UNKNOWN, pythonTestRunner(root, System.nanoTime() - 1))
    }

    private fun modules(root: File): List<BuildModule> = listOf(
        BuildModule(
            "root",
            root.path,
            listOf(root.path),
            PythonProjects.TEST,
            PythonProjects.TYPECHECK,
            true,
            executionId = "root",
        ),
    )

    private companion object {
        const val PYTHON_RUNNER_DISCOVERY_FAILURE =
            "import sys; sys.stderr.write(\"Affected could not safely determine whether this project uses " +
                "pytest or unittest; remove test-tree symlinks or declare pytest.\\n\"); raise SystemExit(2)"
    }
}
