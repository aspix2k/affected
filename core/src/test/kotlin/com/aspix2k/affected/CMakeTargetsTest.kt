package com.aspix2k.affected

import com.aspix2k.affected.build.cmake.CMakeBuildSystem
import com.aspix2k.affected.build.cmake.CMakeTargets
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CMakeTargetsTest {

    private fun project(): File = createTempDirectory("cmake").toFile()

    private fun lists(root: File, path: String, body: String) {
        val directory = File(root, path).apply { mkdirs() }
        File(directory, "CMakeLists.txt").writeText(body.trimIndent())
    }

    @Test
    fun `libraries and executables become modules`() {
        val root = project()
        lists(root, "src/core", "add_library(core STATIC core.cpp)")
        lists(root, "src/app", "add_executable(app main.cpp)")

        assertEquals(setOf("core", "app"), CMakeTargets.parse(root).map { it.id }.toSet())
    }

    @Test
    fun `linking targets creates a graph edge`() {
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

        assertEquals(setOf("CMAKE|${root.invariantSeparatorsPath}|core"), app.dependencies)
    }

    @Test
    fun `visibility keywords are not targets`() {
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
            setOf("CMAKE|${root.invariantSeparatorsPath}|core"),
            app.dependencies,
            "PUBLIC and PRIVATE are modifiers, and pthread is not declared in the project",
        )
    }

    @Test
    fun `a registered test makes the project testable without inferring target ownership`() {
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

        assertTrue(modules.all { it.hasTests })
    }

    @Test
    fun `build directories are not scanned`() {
        val root = project()
        lists(root, "src/core", "add_library(core STATIC core.cpp)")
        lists(root, "src/app", "add_executable(app main.cpp)")
        lists(root, "cmake-build-debug/generated", "add_library(ghost STATIC ghost.cpp)")

        assertEquals(setOf("core", "app"), CMakeTargets.parse(root).map { it.id }.toSet())
    }

    @Test
    fun `a project with one target remains runnable`() {
        val root = project()
        lists(root, ".", "add_executable(single main.cpp)")

        val module = CMakeTargets.parse(root).single()

        assertEquals("single", module.id)
        assertEquals("build", module.testTask)
        assertTrue(module.hasTests)
    }

    @Test
    fun `an external library is not a consumer`() {
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

        assertEquals(setOf("CMAKE|${root.invariantSeparatorsPath}|a"), b.dependencies)
    }

    private fun owners(root: File, source: String): List<Pair<String, String>> {
        val system = CMakeBuildSystem()
        val graph = ModuleGraph(CMakeTargets.parse(root).map { ModuleGraph.Node(it, system) })
        return graph.nodesFor(File(root, source)).map { it.id to it.module.testTask }
    }

    @Test
    fun `a function declared target is no junk module and its source falls back to a module running tests`() {
        val root = project()
        lists(root, ".", "add_library(fmt STATIC src/format.cc)\nadd_subdirectory(test)")
        lists(
            root,
            "test",
            """
            function(add_fmt_test name)
              add_executable(${'$'}{name} ${'$'}{name}.cc)
              add_executable(test-${'$'}{name} ${'$'}{name}.cc)
              add_test(NAME ${'$'}{name} COMMAND ${'$'}{name})
            endfunction()
            add_fmt_test(format-test)
            """,
        )

        assertEquals(listOf("fmt"), CMakeTargets.parse(root).map { it.id })
        assertEquals(listOf("fmt" to "test"), owners(root, "test/format-test.cc"))
    }

    @Test
    fun `an interpolated or separator terminated name is dropped while literal names stay`() {
        val root = project()
        lists(
            root,
            ".",
            """
            add_executable(test-${'$'}{name} a.cc)
            add_library(lib_${'$'}{suffix} STATIC b.cc)
            add_executable(${'$'}<TARGET_NAME:x> c.cc)
            add_executable(literal d.cc)
            add_test(NAME literal COMMAND literal)
            """,
        )

        assertEquals(listOf("literal"), CMakeTargets.parse(root).map { it.id })
    }

    @Test
    fun `a plain executable with a test owns its own source`() {
        val root = project()
        lists(root, ".", "add_library(core STATIC core.cc)")
        lists(
            root,
            "tests",
            """
            add_executable(core_tests test.cc)
            add_test(NAME core_tests COMMAND core_tests)
            """,
        )

        assertEquals(listOf("core_tests" to "test"), owners(root, "tests/test.cc"))
    }
}
