package com.aspix2k.affected

import com.aspix2k.affected.build.CMakeTargets
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CMakeTargetsTest {

    private fun project(): File = createTempDirectory("cmake").toFile()

    private fun lists(root: File, path: String, body: String) {
        val directory = File(root, path).apply { mkdirs() }
        File(directory, "CMakeLists.txt").writeText(body.trimIndent())
    }

    @Test
    fun `библиотеки и исполняемые файлы становятся модулями`() {
        val root = project()
        lists(root, "src/core", "add_library(core STATIC core.cpp)")
        lists(root, "src/app", "add_executable(app main.cpp)")

        assertEquals(setOf("core", "app"), CMakeTargets.parse(root).map { it.id }.toSet())
    }

    @Test
    fun `связывание целей даёт ребро графа`() {
        val root = project()
        lists(root, "src/core", "add_library(core STATIC core.cpp)")
        lists(
            root,
            "src/app",
            """
            add_executable(app main.cpp)
            target_link_libraries(app PRIVATE core)
            """,
        )

        val app = CMakeTargets.parse(root).single { it.id == "app" }

        assertEquals(setOf("${root.invariantSeparatorsPath}|core"), app.dependencies)
    }

    @Test
    fun `ключевые слова видимости не считаются целями`() {
        val root = project()
        lists(root, "src/core", "add_library(core STATIC core.cpp)")
        lists(
            root,
            "src/app",
            """
            add_executable(app main.cpp)
            target_link_libraries(app PUBLIC core PRIVATE pthread)
            """,
        )

        val app = CMakeTargets.parse(root).single { it.id == "app" }

        assertEquals(
            setOf("${root.invariantSeparatorsPath}|core"),
            app.dependencies,
            "PUBLIC и PRIVATE — модификаторы, а pthread не объявлен в проекте",
        )
    }

    @Test
    fun `зарегистрированный тест делает цель тестируемой`() {
        val root = project()
        lists(root, "src/core", "add_library(core STATIC core.cpp)")
        lists(
            root,
            "tests",
            """
            add_executable(core_tests test.cpp)
            add_test(NAME core_tests COMMAND core_tests)
            """,
        )

        val modules = CMakeTargets.parse(root)

        assertTrue(modules.single { it.id == "core_tests" }.hasTests)
        assertFalse(modules.single { it.id == "core" }.hasTests)
    }

    @Test
    fun `каталоги сборки не просматриваются`() {
        val root = project()
        lists(root, "src/core", "add_library(core STATIC core.cpp)")
        lists(root, "src/app", "add_executable(app main.cpp)")
        lists(root, "cmake-build-debug/generated", "add_library(ghost STATIC ghost.cpp)")

        assertEquals(setOf("core", "app"), CMakeTargets.parse(root).map { it.id }.toSet())
    }

    @Test
    fun `проект с одной целью не даёт модулей`() {
        val root = project()
        lists(root, ".", "add_executable(single main.cpp)")

        assertEquals(emptyList(), CMakeTargets.parse(root))
    }

    @Test
    fun `внешняя библиотека потребителем не считается`() {
        val root = project()
        lists(root, "src/a", "add_library(a STATIC a.cpp)")
        lists(
            root,
            "src/b",
            """
            add_library(b STATIC b.cpp)
            target_link_libraries(b PRIVATE a Boost::filesystem)
            """,
        )

        val b = CMakeTargets.parse(root).single { it.id == "b" }

        assertEquals(setOf("${root.invariantSeparatorsPath}|a"), b.dependencies)
    }
}
