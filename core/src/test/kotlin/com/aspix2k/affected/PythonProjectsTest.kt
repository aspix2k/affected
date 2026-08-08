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
    fun `монорепо из нескольких пакетов даёт модули`() {
        val root = workspace()
        packageAt(root, ".", "root-app")
        packageAt(root, "libs/core", "app-core")
        packageAt(root, "libs/api", "app-api")

        val modules = PythonProjects.parse(root)

        assertEquals(setOf("root-app", "app-core", "app-api"), modules.map { it.id }.toSet())
    }

    @Test
    fun `одиночный пакет не считается монорепо`() {
        val root = workspace()
        packageAt(root, ".", "single")

        assertEquals(
            emptyList(),
            PythonProjects.parse(root),
            "для одного пакета выборочный прогон не имеет смысла",
        )
    }

    @Test
    fun `зависимостями считаются только свои пакеты`() {
        val root = workspace()
        packageAt(root, "libs/core", "app-core")
        packageAt(root, "libs/api", "app-api", dependencies = listOf("app-core>=1.0", "httpx[cli]>=0.27"))

        val api = PythonProjects.parse(root).single { it.id == "app-api" }

        assertEquals(
            setOf("${root.invariantSeparatorsPath}|app-core"),
            api.dependencies,
            "httpx ставится из индекса и потребителем быть не может",
        )
    }

    @Test
    fun `потребителя проверяем только когда настроен mypy`() {
        val root = workspace()
        packageAt(root, "libs/typed", "typed-pkg", mypy = true)
        packageAt(root, "libs/plain", "plain-pkg")

        val modules = PythonProjects.parse(root)

        assertEquals("typecheck", modules.single { it.id == "typed-pkg" }.compileTask)
        assertNull(
            modules.single { it.id == "plain-pkg" }.compileTask,
            "без mypy проверить потребителя нечем",
        )
    }

    @Test
    fun `каталог tests делает пакет тестируемым`() {
        val root = workspace()
        packageAt(root, "libs/with", "with-tests", tests = true)
        packageAt(root, "libs/without", "without-tests")

        val modules = PythonProjects.parse(root)

        assertTrue(modules.single { it.id == "with-tests" }.hasTests)
    }

    @Test
    fun `файл test_ рядом с кодом тоже считается тестами`() {
        val root = workspace()
        packageAt(root, "libs/a", "pkg-a")
        packageAt(root, "libs/b", "pkg-b")
        File(root, "libs/a/test_thing.py").writeText("def test_thing(): pass\n")

        val modules = PythonProjects.parse(root)

        assertTrue(modules.single { it.id == "pkg-a" }.hasTests)
    }

    @Test
    fun `версии зависимостей отбрасываются`() {
        val root = workspace()
        packageAt(root, "libs/core", "app-core")
        packageAt(root, "libs/api", "app-api", dependencies = listOf("app-core == 2.1.0"))

        val api = PythonProjects.parse(root).single { it.id == "app-api" }

        assertEquals(setOf("${root.invariantSeparatorsPath}|app-core"), api.dependencies)
    }
}
