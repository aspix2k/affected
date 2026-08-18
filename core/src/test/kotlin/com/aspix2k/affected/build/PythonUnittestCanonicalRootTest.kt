package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class PythonUnittestCanonicalRootTest {

    @Test
    fun `a case-normalized project root keeps exact unittest selection`() {
        val temporary = createTempDirectory("python-unittest-root-alias").toFile()
        File(temporary, "ProjectRoot").mkdirs()
        val root = File(temporary, "projectroot")
        assumeTrue(root.isDirectory)
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        val selected = File(root, "packages/a/test_alpha.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
        }
        val adapter = File(temporary, "affected_unittest.py").apply { writeText("# adapter\n") }.toPath()

        try {
            val arguments = pythonCommands(
                root.path,
                listOf("pkg-a:test"),
                unittestModules(root, "pkg-a", "packages/a"),
                unittestChanges(selected),
                adapter,
            ).single().arguments

            assertExactUnittestSelection(arguments, adapter, "packages/a", "packages/a/test_alpha.py")
        } finally {
            assertTrue(temporary.deleteRecursively(), "Could not delete $temporary")
        }
    }

    @Test
    fun `a real-path selected file keeps exact unittest selection on a lexical root`() {
        val root = createTempDirectory("python-unittest-real-selected").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        val selected = File(root, "packages/a/test_alpha.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
        }
        val adapter = File(root, "affected_unittest.py").apply { writeText("# adapter\n") }.toPath()
        val canonical = selected.toPath().toRealPath().toFile()

        val arguments = pythonCommands(
            root.path,
            listOf("pkg-a:test"),
            unittestModules(root, "pkg-a", "packages/a"),
            unittestChanges(canonical),
            adapter,
        ).single().arguments

        assertExactUnittestSelection(arguments, adapter, "packages/a", "packages/a/test_alpha.py")
    }
}
