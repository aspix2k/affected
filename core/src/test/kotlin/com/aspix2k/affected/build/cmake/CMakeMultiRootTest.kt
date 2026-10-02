package com.aspix2k.affected.build.cmake

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import com.aspix2k.affected.build.multiRootProject
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CMakeMultiRootTest {

    @Test
    fun `two CMake projects without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("cmake-multi").toFile()
        val roots = listOf("engine", "tools").map { cmake(base, "native/$it", "out-$it") }
        val system = CMakeBuildSystem()
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), modules.map { it.root }.toSet())
        assertEquals(setOf("app", "app_test"), modules.map { it.id }.toSet())
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), plan.groups.map { it.root }.toSet())
        plan.groups.forEach { group ->
            val commands = cmakeCommands(group.root, group.tasks)
            val build = "out-${group.root.substringAfterLast('/')}"
            assertEquals(listOf("cmake", "--build", build), commands.first().arguments.take(3), group.root)
            assertEquals(listOf("ctest", "--test-dir", build), commands.last().arguments.take(3), group.root)
        }
    }

    @Test
    fun `sibling roots never share a baseline directory`() {
        val system = Path.of("system")
        val one = cmakeCacheDirectory(system, "hash", Path.of("/repo/native/engine"))
        val two = cmakeCacheDirectory(system, "hash", Path.of("/repo/native/tools"))

        assertNotEquals(one, two)
        assertEquals(one, cmakeCacheDirectory(system, "hash", Path.of("/repo/native/engine")))
    }

    private fun cmake(base: File, path: String, tree: String): File = File(base, path).also {
        File(it, tree).mkdirs()
        File(it, "$tree/CMakeCache.txt").writeText("")
        File(it, "CMakeLists.txt").writeText(
            "add_executable(app main.cpp)\nadd_executable(app_test test.cpp)\n" +
                "target_link_libraries(app_test app)\nadd_test(NAME unit COMMAND app_test)\n",
        )
    }
}
