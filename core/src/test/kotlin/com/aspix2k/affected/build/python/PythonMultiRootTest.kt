package com.aspix2k.affected.build.python

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import com.intellij.openapi.project.Project
import java.io.File
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PythonMultiRootTest {

    @Test
    fun `two Python projects without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("python-multi").toFile()
        val roots = listOf("api", "worker").map { project(base, "services/$it", it) }
        val system = PythonBuildSystem()
        val project = project(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), modules.map { it.root }.toSet())
        assertEquals(setOf("api", "worker"), modules.map { it.id }.toSet())
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), plan.groups.map { it.root }.toSet())
        plan.groups.forEach { group ->
            val owned = modules.filter { it.executionRoot == group.root }
            assertEquals(1, pythonCommands(group.root, group.tasks, owned).size, group.root)
        }
    }

    private fun project(base: File, path: String, name: String): File = File(base, path).also {
        File(it, "tests").mkdirs()
        File(it, "pyproject.toml").writeText("[project]\nname = \"$name\"\n")
    }

    private fun project(root: File): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getBasePath" -> root.path
            else -> error("Unexpected Project call: ${method.name}")
        }
    } as Project
}
