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
    fun `npm workspaces раскрываются из package json`() {
        val root = workspace("""{ "name": "root", "workspaces": ["packages/*"] }""")
        addPackage(root, "packages/core", "@app/core")
        addPackage(root, "packages/ui", "@app/ui")

        val modules = NodeWorkspaces.parse(root)

        assertEquals(setOf("@app/core", "@app/ui"), modules.map { it.id }.toSet())
    }

    @Test
    fun `pnpm workspaces читаются из своего файла`() {
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
    fun `зависимостями считаются только пакеты воркспейса`() {
        val root = workspace("""{ "name": "root", "workspaces": ["packages/*"] }""")
        addPackage(root, "packages/core", "@app/core")
        addPackage(root, "packages/ui", "@app/ui", dependencies = """{ "@app/core": "workspace:*", "react": "^18" }""")

        val ui = NodeWorkspaces.parse(root).single { it.id == "@app/ui" }

        assertEquals(
            setOf("${root.invariantSeparatorsPath}|@app/core"),
            ui.dependencies,
            "react ставится из реестра и потребителем быть не может",
        )
    }

    @Test
    fun `потребителя проверяем только когда есть typescript`() {
        val root = workspace("""{ "name": "root", "workspaces": ["packages/*"] }""")
        addPackage(root, "packages/typed", "@app/typed", typed = true)
        addPackage(root, "packages/plain", "@app/plain")

        val modules = NodeWorkspaces.parse(root)

        assertEquals("typecheck", modules.single { it.id == "@app/typed" }.compileTask)
        assertNull(
            modules.single { it.id == "@app/plain" }.compileTask,
            "в чистом javascript компилировать нечего",
        )
    }

    @Test
    fun `скрипт test делает пакет тестируемым`() {
        val root = workspace("""{ "name": "root", "workspaces": ["packages/*"] }""")
        addPackage(root, "packages/tested", "@app/tested", scripts = """{ "test": "vitest" }""")
        addPackage(root, "packages/bare", "@app/bare")

        val modules = NodeWorkspaces.parse(root)

        assertTrue(modules.single { it.id == "@app/tested" }.hasTests)
        assertFalse(modules.single { it.id == "@app/bare" }.hasTests)
    }

    @Test
    fun `node_modules не попадает в пакеты`() {
        val root = workspace("""{ "name": "root", "workspaces": ["packages/*"] }""")
        addPackage(root, "packages/core", "@app/core")
        addPackage(root, "packages/node_modules", "should-not-appear")

        val modules = NodeWorkspaces.parse(root)

        assertEquals(listOf("@app/core"), modules.map { it.id })
    }

    @Test
    fun `проект без воркспейсов не даёт модулей`() {
        val root = workspace("""{ "name": "single-package" }""")

        assertEquals(emptyList(), NodeWorkspaces.parse(root))
    }

    @Test
    fun `реальный pnpm-воркспейс vite разбирается`() {
        assumeTrue(FixtureRepository.available("npm-vite"))
        val root = File(FixtureRepository.root, "npm-vite")

        val modules = NodeWorkspaces.parse(root)

        assertTrue(modules.size >= 3, "в vite несколько пакетов, разобрали ${modules.size}")
        assertTrue(modules.all { File(it.contentRoots.single()).isDirectory }, "каталоги должны существовать")
    }

    @Test
    fun `реальный yarn-воркспейс babel разбирается`() {
        assumeTrue(FixtureRepository.available("npm-babel"))
        val root = File(FixtureRepository.root, "npm-babel")

        val modules = NodeWorkspaces.parse(root)

        assertTrue(modules.size >= 10, "в babel десятки пакетов, разобрали ${modules.size}")
        assertTrue(modules.any { it.dependencies.isNotEmpty() }, "между пакетами babel есть зависимости")
    }
}
