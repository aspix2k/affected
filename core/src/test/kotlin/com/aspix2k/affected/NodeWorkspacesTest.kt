package com.aspix2k.affected

import com.aspix2k.affected.build.NodeWorkspaces
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeWorkspacesTest {

    private fun workspace(rootManifest: String, pnpm: String? = null): File {
        val root = createTempDirectory("node-ws").toFile()
        File(root, "package.json").writeText(rootManifest)
        pnpm?.let { File(root, "pnpm-workspace.yaml").writeText(it) }
        return root
    }

    private fun addPackage(
        root: File,
        path: String,
        name: String,
        dependencies: String = "{}",
        scripts: String = "{}",
        typed: Boolean = false,
    ) {
        val directory = File(root, path).apply { mkdirs() }
        File(directory, "package.json").writeText(
            """{ "name": "$name", "dependencies": $dependencies, "scripts": $scripts }""",
        )
        if (typed) File(directory, "tsconfig.json").writeText("{}")
    }

    @Test
    fun `npm workspaces expand from package json`() {
        val root = workspace("""{ "name": "root", "workspaces": ["packages/*"] }""")
        addPackage(root, "packages/core", "@app/core")
        addPackage(root, "packages/ui", "@app/ui")

        val modules = NodeWorkspaces.parse(root)

        assertEquals(setOf("@app/core", "@app/ui"), modules.map { it.id }.toSet())
    }

    @Test
    fun `pnpm workspaces are read from their own file`() {
        val root = workspace(
            """{ "name": "root" }""",
            pnpm = "packages:\n  - 'packages/*'\n  - docs\n",
        )
        addPackage(root, "packages/core", "@app/core")
        File(root, "docs").mkdirs()
        File(root, "docs/package.json").writeText("""{ "name": "@app/docs" }""")

        val modules = NodeWorkspaces.parse(root)

        assertEquals(setOf("@app/core", "@app/docs"), modules.map { it.id }.toSet())
    }

    @Test
    fun `only workspace packages are dependencies`() {
        val root = workspace("""{ "name": "root", "workspaces": ["packages/*"] }""")
        addPackage(root, "packages/core", "@app/core")
        addPackage(root, "packages/ui", "@app/ui", dependencies = """{ "@app/core": "workspace:*", "react": "^18" }""")

        val ui = NodeWorkspaces.parse(root).single { it.id == "@app/ui" }

        assertEquals(
            setOf("${root.invariantSeparatorsPath}|@app/core"),
            ui.dependencies,
            "React comes from the registry and cannot be a consumer",
        )
    }

    @Test
    fun `a consumer is checked only when TypeScript is present`() {
        val root = workspace("""{ "name": "root", "workspaces": ["packages/*"] }""")
        addPackage(root, "packages/typed", "@app/typed", typed = true)
        addPackage(root, "packages/plain", "@app/plain")

        val modules = NodeWorkspaces.parse(root)

        assertEquals("typecheck", modules.single { it.id == "@app/typed" }.compileTask)
        assertNull(
            modules.single { it.id == "@app/plain" }.compileTask,
            "plain JavaScript has nothing to compile",
        )
    }

    @Test
    fun `a test script makes a package testable`() {
        val root = workspace("""{ "name": "root", "workspaces": ["packages/*"] }""")
        addPackage(root, "packages/tested", "@app/tested", scripts = """{ "test": "vitest" }""")
        addPackage(root, "packages/bare", "@app/bare")

        val modules = NodeWorkspaces.parse(root)

        assertTrue(modules.single { it.id == "@app/tested" }.hasTests)
        assertFalse(modules.single { it.id == "@app/bare" }.hasTests)
    }

    @Test
    fun `node_modules is excluded from packages`() {
        val root = workspace("""{ "name": "root", "workspaces": ["packages/*"] }""")
        addPackage(root, "packages/core", "@app/core")
        addPackage(root, "packages/node_modules", "should-not-appear")

        val modules = NodeWorkspaces.parse(root)

        assertEquals(listOf("@app/core"), modules.map { it.id })
    }

    @Test
    fun `a project without workspaces yields no modules`() {
        val root = workspace("""{ "name": "single-package" }""")

        assertEquals(emptyList(), NodeWorkspaces.parse(root))
    }

    @Test
    fun `a real Vite pnpm workspace is parsed`() {
        assumeTrue(FixtureRepository.available("npm-vite"))
        val root = File(FixtureRepository.root, "npm-vite")

        val modules = NodeWorkspaces.parse(root)

        assertTrue(modules.size >= 3, "Vite has several packages, parsed ${modules.size}")
        assertTrue(modules.all { File(it.contentRoots.single()).isDirectory }, "directories must exist")
    }

    @Test
    fun `a real Babel Yarn workspace is parsed`() {
        assumeTrue(FixtureRepository.available("npm-babel"))
        val root = File(FixtureRepository.root, "npm-babel")

        val modules = NodeWorkspaces.parse(root)

        assertTrue(modules.size >= 10, "Babel has dozens of packages, parsed ${modules.size}")
        assertTrue(modules.any { it.dependencies.isNotEmpty() }, "Babel packages have dependencies")
    }
}
