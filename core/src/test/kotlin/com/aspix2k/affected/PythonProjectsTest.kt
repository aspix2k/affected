package com.aspix2k.affected

import com.aspix2k.affected.build.PythonProjects
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PythonProjectsTest {

    private fun workspace(): File = createTempDirectory("python").toFile()

    private fun packageAt(
        root: File,
        path: String,
        name: String,
        dependencies: List<String> = emptyList(),
        mypy: Boolean = false,
        tests: Boolean = false,
    ) {
        val directory = File(root, path).apply { mkdirs() }
        val list = dependencies.joinToString(", ") { "\"$it\"" }
        File(directory, "pyproject.toml").writeText(
            buildString {
                appendLine("[project]")
                appendLine("name = \"$name\"")
                appendLine("dependencies = [$list]")
                if (mypy) {
                    appendLine("")
                    appendLine("[tool.mypy]")
                    appendLine("strict = true")
                }
            },
        )
        if (tests) File(directory, "tests").mkdirs()
    }

    @Test
    fun `a multipackage monorepo yields modules`() {
        val root = workspace()
        packageAt(root, ".", "root-app")
        packageAt(root, "libs/core", "app-core")
        packageAt(root, "libs/api", "app-api")

        val modules = PythonProjects.parse(root)

        assertEquals(setOf("root-app", "app-core", "app-api"), modules.map { it.id }.toSet())
    }

    @Test
    fun `a single package is not a monorepo`() {
        val root = workspace()
        packageAt(root, ".", "single")

        assertEquals(
            emptyList(),
            PythonProjects.parse(root),
            "selective execution is pointless for a single package",
        )
    }

    @Test
    fun `only local packages are dependencies`() {
        val root = workspace()
        packageAt(root, "libs/core", "app-core")
        packageAt(root, "libs/api", "app-api", dependencies = listOf("app-core>=1.0", "httpx[cli]>=0.27"))

        val api = PythonProjects.parse(root).single { it.id == "app-api" }

        assertEquals(
            setOf("${root.invariantSeparatorsPath}|app-core"),
            api.dependencies,
            "httpx comes from the package index and cannot be a consumer",
        )
    }

    @Test
    fun `a consumer is checked only when mypy is configured`() {
        val root = workspace()
        packageAt(root, "libs/typed", "typed-pkg", mypy = true)
        packageAt(root, "libs/plain", "plain-pkg")

        val modules = PythonProjects.parse(root)

        assertEquals("typecheck", modules.single { it.id == "typed-pkg" }.compileTask)
        assertNull(
            modules.single { it.id == "plain-pkg" }.compileTask,
            "without mypy there is no consumer check",
        )
    }

    @Test
    fun `a tests directory makes a package testable`() {
        val root = workspace()
        packageAt(root, "libs/with", "with-tests", tests = true)
        packageAt(root, "libs/without", "without-tests")

        val modules = PythonProjects.parse(root)

        assertTrue(modules.single { it.id == "with-tests" }.hasTests)
    }

    @Test
    fun `a test file beside code also counts as tests`() {
        val root = workspace()
        packageAt(root, "libs/a", "pkg-a")
        packageAt(root, "libs/b", "pkg-b")
        File(root, "libs/a/test_thing.py").writeText("def test_thing(): pass\n")

        val modules = PythonProjects.parse(root)

        assertTrue(modules.single { it.id == "pkg-a" }.hasTests)
    }

    @Test
    fun `dependency versions are discarded`() {
        val root = workspace()
        packageAt(root, "libs/core", "app-core")
        packageAt(root, "libs/api", "app-api", dependencies = listOf("app-core == 2.1.0"))

        val api = PythonProjects.parse(root).single { it.id == "app-api" }

        assertEquals(setOf("${root.invariantSeparatorsPath}|app-core"), api.dependencies)
    }
}
