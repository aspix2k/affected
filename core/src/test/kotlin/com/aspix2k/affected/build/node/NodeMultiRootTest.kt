package com.aspix2k.affected.build.node

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import com.aspix2k.affected.build.IdeNodeBuildSystem
import com.intellij.openapi.project.Project
import java.io.File
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeMultiRootTest {

    @Test
    fun `two Node apps without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("node-multi").toFile()
        val roots = listOf("web", "admin").map { app(base, "apps/$it", it) }
        val system = IdeNodeBuildSystem()
        val project = project(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), modules.map { it.root }.toSet())
        assertEquals(setOf("web", "admin"), modules.map { it.id }.toSet())
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), plan.groups.map { it.root }.toSet())
        plan.groups.forEach { group ->
            assertEquals(listOf("npm", "test"), nodeCommands(group.root, group.tasks).single().arguments)
        }
    }

    @Test
    fun `a Node root with workspaces stays one root that owns its members`() {
        val base = createTempDirectory("node-multi-workspaces").toFile()
        val workspace = app(base, "apps/web", "web").also {
            File(it, "package.json").writeText(
                "{\"name\":\"web\",\"workspaces\":[\"packages/*\"],\"scripts\":{\"test\":\"jest\"}}",
            )
            app(it, "packages/ui", "ui")
        }
        app(base, "apps/admin", "admin")

        val modules = IdeNodeBuildSystem().modules(project(base))

        assertEquals(
            setOf("ui"),
            modules.filter { it.root == workspace.invariantSeparatorsPath }.map { it.id }.toSet() - "web",
        )
        assertEquals(setOf("admin"), modules.filter { it.root.endsWith("apps/admin") }.map { it.id }.toSet())
        assertEquals(2, modules.map { it.root }.toSet().size)
    }

    @Test
    fun `a package outside the workspace globs with scripts or a lockfile is its own root`() {
        val base = createTempDirectory("node-independent").toFile()
        File(base, "package.json").writeText("{\"name\":\"root\",\"workspaces\":[\"packages/*\"]}")
        app(base, "packages/ui", "ui")
        app(base, "examples/site", "site")
        File(base, "tools/lock").also { it.mkdirs() }
        File(base, "tools/lock/package.json").writeText("{\"name\":\"lock\"}")
        File(base, "tools/lock/package-lock.json").writeText("{}")
        File(base, "src/esm").also { it.mkdirs() }
        File(base, "src/esm/package.json").writeText("{\"type\":\"module\"}")

        assertEquals(
            listOf("", "examples/site", "tools/lock").map { File(base, it).canonicalFile },
            nodeProjectRoots(base).map(File::getCanonicalFile),
        )
    }

    @Test
    fun `a package below a root without workspaces is its own root`() {
        val base = app(createTempDirectory("node-no-workspace").toFile(), ".", "root")
        app(base, "demo", "demo")

        assertEquals(
            listOf(base, File(base, "demo")).map(File::getCanonicalFile),
            nodeProjectRoots(base).map(File::getCanonicalFile),
        )
    }

    private fun app(base: File, path: String, name: String): File = File(base, path).also {
        File(it, "tests").mkdirs()
        File(it, "package.json").writeText("{\"name\":\"$name\",\"scripts\":{\"test\":\"jest\"}}")
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
