package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PythonUnittestCommandTest {

    @Test
    fun `changed unittest modules become native unittest paths`() {
        val root = createTempDirectory("python-unittest-exact-files").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        val changed = File(root, "packages/a/test_alpha.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
        }
        File(root, "packages/a/test_other.py").writeText(
            "import unittest\nclass OtherTest(unittest.TestCase):\n    pass\n",
        )
        val modules = modules(root, "pkg-a", "packages/a")

        val arguments = pythonCommands(
            root.path,
            listOf("pkg-a:test"),
            modules,
            changes(changed),
        ).single().arguments

        assertEquals(listOf("python", "-m", "unittest", "packages/a/test_alpha.py"), arguments)
    }

    @Test
    fun `a production unittest change keeps package discovery`() {
        val root = createTempDirectory("python-unittest-src-full").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        File(root, "packages/a/test_alpha.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
        }
        val source = File(root, "packages/a/alpha.py").apply {
            writeText("value = 1\n")
        }
        val modules = modules(root, "pkg-a", "packages/a")

        val arguments = pythonCommands(
            root.path,
            listOf("pkg-a:test"),
            modules,
            changes(source),
        ).single().arguments

        assertEquals(
            listOf("python", "-m", "unittest", "discover", "-s", "packages/a", "-t", "."),
            arguments,
        )
    }

    @Test
    fun `an unproved planned unittest package keeps discovery`() {
        val root = createTempDirectory("python-unittest-partial-plan").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        val changed = File(root, "packages/a/test_alpha.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
        }
        File(root, "packages/b/test_beta.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass BetaTest(unittest.TestCase):\n    pass\n")
        }
        val modules = modules(root, "pkg-a", "packages/a", "pkg-b", "packages/b")

        val commands = pythonCommands(
            root.path,
            listOf("pkg-a:test", "pkg-b:test"),
            modules,
            changes(changed),
        )

        assertEquals(2, commands.size)
        assertFalse(commands.any { it.arguments.contains("packages/a/test_alpha.py") })
        assertEquals(
            listOf("python", "-m", "unittest", "discover", "-s", "packages/a", "-t", "."),
            commands.single { it.title == "unittest packages/a" }.arguments,
        )
    }

    @Test
    fun `changes without a merge base keep unittest discovery`() {
        val root = createTempDirectory("python-unittest-no-base").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        val changed = File(root, "packages/a/test_alpha.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
        }
        val modules = modules(root, "pkg-a", "packages/a")

        val arguments = pythonCommands(
            root.path,
            listOf("pkg-a:test"),
            modules,
            BuildChanges(listOf(changed.path), exactSelectionEligible = emptySet(), comparedToBase = false),
        ).single().arguments

        assertEquals(
            listOf("python", "-m", "unittest", "discover", "-s", "packages/a", "-t", "."),
            arguments,
        )
    }

    private fun changes(vararg files: File) = BuildChanges(
        files = files.map(File::getPath),
        exactSelectionEligible = files.mapTo(LinkedHashSet(), File::getPath),
        comparedToBase = true,
    )

    private fun modules(root: File, vararg entries: String): List<BuildModule> {
        require(entries.size % 2 == 0)
        return entries.toList().chunked(2).map { (name, path) ->
            BuildModule(
                name,
                root.path,
                listOf(File(root, path).path),
                PythonProjects.TEST,
                PythonProjects.TYPECHECK,
                true,
                executionId = name,
            )
        }
    }
}
