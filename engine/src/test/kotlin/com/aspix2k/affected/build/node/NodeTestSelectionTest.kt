package com.aspix2k.affected.build.node

import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.process.CliCommand
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeTestSelectionTest {

    @Test
    fun `Jest receives only changed files from its workspace`() {
        val fixture = workspace()
        val changed = fixture.source("alpha", "alpha.js", "export const alpha = 1")
        fixture.test("alpha", "alpha.test.js", "import { alpha } from './alpha.js'")
        fixture.packageManifest("alpha", "jest", "^30.0.0")

        val command = exactCommands(fixture.root, "@app/alpha:test", changed).single()

        assertEquals(
            listOf(
                "npm",
                "exec",
                "--workspace",
                "@app/alpha",
                "--",
                "jest",
                "--findRelatedTests",
                "--passWithNoTests",
                "src/alpha.js",
            ),
            command.arguments,
        )
    }

    @Test
    fun `Vitest receives related files in run mode`() {
        val fixture = workspace()
        val changed = fixture.source("alpha", "alpha.ts", "export const alpha = 1")
        fixture.test("alpha", "alpha.test.ts", "import { alpha } from './alpha'")
        fixture.packageManifest("alpha", "vitest run", "^4.0.0")

        val command = exactCommands(fixture.root, "@app/alpha:test", changed).single()

        assertEquals(
            listOf(
                "npm",
                "exec",
                "--workspace",
                "@app/alpha",
                "--",
                "vitest",
                "related",
                "--run",
                "--passWithNoTests",
                "src/alpha.ts",
            ),
            command.arguments,
        )
    }

    @Test
    fun `a single-package Jest project receives its related source`() {
        val root = createTempDirectory("node-exact-root").toFile()
        File(root, "package.json").writeText(
            """{ "name": "app", "scripts": { "test": "jest" }, "devDependencies": { "jest": "30.0.0" } }""",
        )
        val changed = File(root, "src/alpha.js").apply {
            parentFile.mkdirs()
            writeText("export const alpha = 1")
        }
        File(root, "test/alpha.test.js").apply {
            parentFile.mkdirs()
            writeText("import { alpha } from '../src/alpha.js'")
        }

        val command = exactCommands(root, ".:test", changed).single()

        assertEquals(
            listOf("npm", "exec", "--", "jest", "--findRelatedTests", "--passWithNoTests", "src/alpha.js"),
            command.arguments,
        )
    }

    @Test
    fun `multiple exact workspaces stay in one sequential command batch`() {
        val fixture = workspace()
        val alpha = fixture.source("alpha", "alpha.js", "export const alpha = 1")
        val beta = fixture.source("beta", "beta.js", "export const beta = 2")
        fixture.test("alpha", "alpha.test.js", "import { alpha } from './alpha.js'")
        fixture.test("beta", "beta.test.js", "import { beta } from './beta.js'")
        fixture.packageManifest("alpha", "jest", "^30.0.0")
        fixture.packageManifest("beta", "vitest", "^4.0.0")

        val commands = nodeCommands(
            fixture.root.path,
            listOf("@app/alpha:test", "@app/beta:test"),
            eligibleChanges(alpha, beta),
        )

        assertEquals(2, commands.size)
        assertEquals("jest", commands[0].arguments[5])
        assertEquals("vitest", commands[1].arguments[5])
    }

    @Test
    fun `pnpm and Yarn keep exact workspace isolation`() {
        val pnpm = exactJestWorkspace()
        File(pnpm.root, "pnpm-workspace.yaml").writeText("packages:\n  - 'packages/*'\n")
        val yarn = exactJestWorkspace()
        File(yarn.root, "yarn.lock").writeText("")
        val declaredYarn = exactJestWorkspace()
        File(declaredYarn.root, "package.json").writeText(
            """{ "name": "root", "private": true, "packageManager": "yarn@4.9.2", "workspaces": ["packages/*"] }""",
        )

        assertEquals(
            listOf(
                "pnpm",
                "--filter",
                "@app/alpha",
                "exec",
                "jest",
                "--findRelatedTests",
                "--passWithNoTests",
                "src/alpha.js",
            ),
            exactCommands(pnpm.root, "@app/alpha:test", pnpm.changed).single().arguments,
        )
        assertEquals(
            listOf(
                "yarn",
                "workspace",
                "@app/alpha",
                "exec",
                "jest",
                "--findRelatedTests",
                "--passWithNoTests",
                "src/alpha.js",
            ),
            exactCommands(yarn.root, "@app/alpha:test", yarn.changed).single().arguments,
        )
        assertEquals(
            listOf(
                "yarn",
                "workspace",
                "@app/alpha",
                "exec",
                "jest",
                "--findRelatedTests",
                "--passWithNoTests",
                "src/alpha.js",
            ),
            exactCommands(declaredYarn.root, "@app/alpha:test", declaredYarn.changed).single().arguments,
        )
    }

    @Test
    fun `missing base comparison keeps the native full package command`() {
        val fixture = exactJestWorkspace()

        val commands = nodeCommands(
            fixture.root.path,
            listOf("@app/alpha:test"),
            BuildChanges(listOf(fixture.changed.path), emptySet(), comparedToBase = false),
        )

        assertEquals(listOf("npm", "test", "--workspace", "@app/alpha"), commands.single().arguments)
    }

    @Test
    fun `unknown scripts and versions keep the full package command`() {
        val unknownScript = workspace().apply {
            source("alpha", "alpha.js", "export const alpha = 1")
            test("alpha", "alpha.test.js", "import { alpha } from './alpha.js'")
            packageManifest("alpha", "node --test", "30.0.0", dependency = "jest")
        }
        val unknownVersion = workspace().apply {
            source("alpha", "alpha.js", "export const alpha = 1")
            test("alpha", "alpha.test.js", "import { alpha } from './alpha.js'")
            packageManifest("alpha", "jest", "31.0.0")
        }
        val prerelease = workspace().apply {
            source("alpha", "alpha.js", "export const alpha = 1")
            test("alpha", "alpha.test.js", "import { alpha } from './alpha.js'")
            packageManifest("alpha", "jest", "30.0.0-rc.1")
        }

        assertFull(unknownScript, File(unknownScript.root, "packages/alpha/src/alpha.js"))
        assertFull(unknownVersion, File(unknownVersion.root, "packages/alpha/src/alpha.js"))
        assertFull(prerelease, File(prerelease.root, "packages/alpha/src/alpha.js"))
    }

    @Test
    fun `added source files keep the full package command`() {
        val exact = exactJestWorkspace()

        assertEquals(
            listOf("npm", "test", "--workspace", "@app/alpha"),
            nodeCommands(
                exact.root.path,
                listOf("@app/alpha:test"),
                BuildChanges(listOf(exact.changed.path), emptySet(), comparedToBase = true),
            ).single().arguments,
        )
    }

    @Test
    fun `custom config and transforms keep the full package command`() {
        val config = exactJestWorkspace()
        File(config.root, "packages/alpha/jest.config.js").writeText("export default {}")
        val transform = exactJestWorkspace(transform = true)

        assertFull(config.fixture, config.changed)
        assertFull(transform.fixture, transform.changed)
    }

    @Test
    fun `dependency overrides keep the full package command`() {
        val exact = exactJestWorkspace()
        val manifest = File(exact.root, "packages/alpha/package.json")
        manifest.writeText(manifest.readText().replace("\n}", ",\n  \"overrides\": { \"jest\": \"31.0.0\" }\n}"))

        assertFull(exact.fixture, exact.changed)
    }

    @Test
    fun `ambiguous package managers and runner declarations keep the full package command`() {
        val managers = exactJestWorkspace()
        File(managers.root, "yarn.lock").writeText("")
        File(managers.root, "package-lock.json").writeText("{}")
        val unsupported = exactJestWorkspace()
        File(unsupported.root, "bun.lock").writeText("")
        val dependencies = exactJestWorkspace()
        val manifest = File(dependencies.root, "packages/alpha/package.json")
        manifest.writeText(
            manifest.readText().replace(
                "\"devDependencies\": { \"jest\": \"^30.0.0\" }",
                "\"dependencies\": { \"jest\": \"^29.0.0\" },\n  \"devDependencies\": { \"jest\": \"^30.0.0\" }",
            ),
        )

        assertEquals(
            listOf("yarn", "workspace", "@app/alpha", "test"),
            exactCommands(managers.root, "@app/alpha:test", managers.changed).single().arguments,
        )
        assertEquals(
            listOf("bun", "--filter", "@app/alpha", "test"),
            exactCommands(unsupported.root, "@app/alpha:test", unsupported.changed).single().arguments,
        )
        assertFull(dependencies.fixture, dependencies.changed)
    }

    @Test
    fun `root runner configuration keeps workspace tests full`() {
        val config = exactJestWorkspace()
        File(config.root, "jest.config.mjs").writeText("export default {}")
        val transform = exactJestWorkspace()
        File(transform.root, "package.json").writeText(
            """
            {
              "name": "root",
              "private": true,
              "workspaces": ["packages/*"],
              "devDependencies": { "babel-jest": "^30.0.0" }
            }
            """.trimIndent(),
        )

        assertFull(config.fixture, config.changed)
        assertFull(transform.fixture, transform.changed)
    }

    @Test
    fun `root compiler configuration keeps every workspace test full`() {
        val exact = exactJestWorkspace()
        val rootConfig = File(exact.root, "tsconfig.json").apply { writeText("{}") }

        assertFull(exact.fixture, rootConfig)
    }

    @Test
    fun `dynamic dependencies keep the full package command`() {
        val dynamicImport = exactJestWorkspace(
            extraSource = "export const load = name => import(`./${'$'}{name}.js`)",
        )
        val dynamicRequire = exactJestWorkspace(
            extraSource = "export const load = name => require(name)",
        )

        assertTrue(hasDynamicNodeDependency("const load = import /* bundled */ ('./alpha.js')"))
        assertTrue(hasDynamicNodeDependency("const load = require(name)"))
        assertTrue(hasDynamicNodeDependency("const load = require"))
        assertTrue(hasDynamicNodeDependency("const load = module.require('./alpha.js')"))
        assertTrue(hasDynamicNodeDependency("const modules = import.meta.glob('./*.js')"))
        assertTrue(hasDynamicNodeDependency("const require = createRequire(import.meta.url)"))
        assertTrue(hasDynamicNodeDependency("const load = eval('requ' + 'ire')"))
        assertTrue(hasDynamicNodeDependency("const load = Function('name', source)"))
        assertFalse(hasDynamicNodeDependency("const load = require('./alpha.js')"))
        assertFull(dynamicImport.fixture, dynamicImport.changed)
        assertFull(dynamicRequire.fixture, dynamicRequire.changed)
    }

    @Test
    fun `resources deletions and root lockfiles keep the full package command`() {
        val resource = exactJestWorkspace()
        val resourceFile = File(resource.root, "packages/alpha/src/schema.json").apply { writeText("{}") }
        val deleted = exactJestWorkspace()
        deleted.changed.delete()
        val lockfile = exactJestWorkspace()
        val lock = File(lockfile.root, "package-lock.json").apply { writeText("{}") }

        assertFull(resource.fixture, resourceFile)
        assertFull(deleted.fixture, deleted.changed)
        assertFull(lockfile.fixture, lock)
    }

    @Test
    fun `generated source changes keep the full package command`() {
        val exact = exactJestWorkspace()
        val generated = File(exact.root, "packages/alpha/dist/generated.js").apply {
            parentFile.mkdirs()
            writeText("export const generated = 1")
        }

        assertFull(exact.fixture, generated)
    }

    @Test
    fun `IDE, editor and hosting directories are scanned like any other and do not block exact selection`() {
        val exact = exactJestWorkspace()
        val alpha = File(exact.root, "packages/alpha")
        File(alpha, ".idea/workspace.xml").apply { parentFile.mkdirs() }.writeText("<project/>")
        File(alpha, ".github/workflows/ci.yml").apply { parentFile.mkdirs() }.writeText("on: push")
        File(alpha, ".vscode/settings.json").apply { parentFile.mkdirs() }.writeText("{}")
        File(alpha, ".husky/pre-commit").apply { parentFile.mkdirs() }.writeText("npm test")
        File(alpha, ".github/scripts/label.js").apply { parentFile.mkdirs() }.writeText("export const label = 1")

        val command = exactCommands(exact.fixture.root, "@app/alpha:test", exact.changed).single()

        assertTrue("--findRelatedTests" in command.arguments, command.arguments.toString())
    }

    @Test
    fun `a dynamic dependency inside a tooling directory still keeps the full package command`() {
        val exact = exactJestWorkspace()
        File(exact.root, "packages/alpha/.github/scripts/load.js").apply {
            parentFile.mkdirs()
            writeText("export const load = name => import(name)")
        }

        assertFull(exact.fixture, exact.changed)
    }

    @Test
    fun `a change inside a tooling directory keeps the full package command`() {
        val exact = exactJestWorkspace()
        val script = File(exact.root, "packages/alpha/.github/scripts/label.js").apply {
            parentFile.mkdirs()
            writeText("export const label = 1")
        }

        assertFull(exact.fixture, script)
    }

    @Test
    fun `an unknown hidden source directory keeps the full package command`() {
        val exact = exactJestWorkspace()
        File(exact.root, "packages/alpha/.runtime/loader.js").apply {
            parentFile.mkdirs()
            writeText("export const load = name => import(name)")
        }

        assertFull(exact.fixture, exact.changed)
    }

    @Test
    fun `a source symlink keeps the full package command`() {
        val exact = exactJestWorkspace()
        val outside = createTempDirectory("node-exact-outside").resolve("linked.js")
        Files.writeString(outside, "export const linked = 1")
        val link = exact.root.toPath().resolve("packages/alpha/src/linked.js")
        assumeTrue(runCatching { Files.createSymbolicLink(link, outside) }.isSuccess)

        assertFull(exact.fixture, exact.changed)
    }

    @Test
    fun `a package that reads files or starts processes keeps the full package command`() {
        listOf(
            "import { execFileSync } from 'node:child_process'",
            "const { fork } = require(\"child_process\")",
            "import { Worker } from 'node:worker_threads'",
            "import { readFileSync } from 'fs'",
            "import { readFile } from 'node:fs/promises'",
            "import vm from 'node:vm'",
            "import { register } from 'node:module'",
            "import { execa } from 'execa'",
            "import { glob } from 'tinyglobby'",
            "const worker = new Worker(url)",
            "await expect(output).toMatchFileSnapshot('./expected.js')",
        ).forEach { source ->
            val workspace = exactJestWorkspace(extraSource = source)

            assertTrue(reachesNodeFilesWithoutImport(source), source)
            assertFull(workspace.fixture, workspace.changed)
        }
        assertFalse(reachesNodeFilesWithoutImport("import path from 'node:path'\nconst fs = options.fs"))
        listOf(
            "import { writeFileSync, mkdirSync as make } from 'node:fs'",
            "const { writeFileSync, rmSync: remove } = require(\"fs\")",
            "import { writeFile } from 'node:fs/promises'",
        ).forEach { source -> assertFalse(reachesNodeFilesWithoutImport(source), source) }
        listOf(
            "import { writeFileSync, readFileSync as write } from 'node:fs'",
            "const { writeFileSync, readFileSync: writeFile } = require('fs')",
            "import { writeFileSync }, fs from 'node:fs'",
            "import * as files from 'node:fs'",
        ).forEach { source -> assertTrue(reachesNodeFilesWithoutImport(source), source) }
    }

    @Test
    fun `a changed test file still runs alone when its package reads files`() {
        val workspace = testFilesWorkspace("node --test")
        workspace.fixture.source("alpha", "files.js", "import { readFileSync } from 'node:fs'")

        assertEquals(
            listOf("npm", "test", "--workspace", "@app/alpha", "--", "test/alpha.test.js"),
            exactCommands(workspace.root, "@app/alpha:test", workspace.changed).single().arguments,
        )
    }

    @Test
    fun `node test receives only changed test files for every manager`() {
        val npm = testFilesWorkspace("node --test")
        val pnpm = testFilesWorkspace("node --test").also { File(it.root, "pnpm-lock.yaml").writeText("") }
        val yarn = testFilesWorkspace("node --test").also { File(it.root, "yarn.lock").writeText("") }

        assertEquals(
            listOf("npm", "test", "--workspace", "@app/alpha", "--", "test/alpha.test.js"),
            exactCommands(npm.root, "@app/alpha:test", npm.changed).single().arguments,
        )
        assertEquals(
            listOf("pnpm", "--filter", "@app/alpha", "test", "test/alpha.test.js"),
            exactCommands(pnpm.root, "@app/alpha:test", pnpm.changed).single().arguments,
        )
        assertEquals(
            listOf("yarn", "workspace", "@app/alpha", "test", "test/alpha.test.js"),
            exactCommands(yarn.root, "@app/alpha:test", yarn.changed).single().arguments,
        )
    }

    @Test
    fun `node test keeps the full package command without a pure test file change`() {
        val exact = testFilesWorkspace("node --test")
        val source = exact.fixture.source("alpha", "alpha.js", "module.exports = 1")
        val typed = exact.fixture.test("alpha", "typed.test.ts", "")
        val helper = exact.fixture.test("alpha", "helper.js", "")
        val spaced = exact.fixture.test("alpha", "a b.test.js", "")
        val referenced = testFilesWorkspace("node --test")
        referenced.fixture.source("alpha", "runner.js", "require('../test/alpha.test.js')")

        listOf(source, typed, helper, spaced).forEach { changed -> assertFull(exact.fixture, changed) }
        assertFull(referenced.fixture, referenced.changed)
        assertEquals(
            listOf("npm", "test", "--workspace", "@app/alpha"),
            nodeCommands(
                exact.root.path,
                listOf("@app/alpha:test"),
                eligibleChanges(exact.changed, source),
            ).single().arguments,
        )
    }

    @Test
    fun `an extensionless import of a changed test keeps the full package command`() {
        listOf(
            "import './alpha.test'",
            "require('../test/alpha.test')",
            "import '../test/alpha.test.js'",
            "await import(`./alpha.test.mjs`)",
        ).forEach { reference ->
            val node = testFilesWorkspace("node --test")
            node.fixture.source("alpha", "runner.js", reference)
            assertFull(node.fixture, node.changed)
        }
        val bun = testFilesWorkspace("bun test", "alpha.spec.ts").also { File(it.root, "bun.lock").writeText("") }
        bun.fixture.source("alpha", "runner.ts", "import '../test/alpha.spec'")
        assertEquals(
            listOf("bun", "--filter", "@app/alpha", "test"),
            exactCommands(bun.root, "@app/alpha:test", bun.changed).single().arguments,
        )
    }

    @Test
    fun `an unrelated name sharing a prefix does not block exact test selection`() {
        val node = testFilesWorkspace("node --test")
        node.fixture.source("alpha", "runner.js", "require('./beta-alpha.test')")

        assertEquals(
            listOf("npm", "test", "--workspace", "@app/alpha", "--", "test/alpha.test.js"),
            exactCommands(node.root, "@app/alpha:test", node.changed).single().arguments,
        )
    }

    @Test
    fun `node test keeps the full package command under a Bun manager or with runner configuration`() {
        val bun = testFilesWorkspace("node --test").also { File(it.root, "bun.lock").writeText("") }
        val config = testFilesWorkspace("node --test")
        File(config.root, "packages/alpha/node.config.json").writeText("{}")

        assertEquals(
            listOf("bun", "--filter", "@app/alpha", "test"),
            exactCommands(bun.root, "@app/alpha:test", bun.changed).single().arguments,
        )
        assertFull(config.fixture, config.changed)
    }

    @Test
    fun `Bun receives changed test files through its package filter`() {
        val exact = testFilesWorkspace("bun test", "alpha.spec.ts").also { File(it.root, "bun.lock").writeText("") }

        assertEquals(
            listOf("bun", "--filter", "@app/alpha", "test", "./test/alpha.spec.ts"),
            exactCommands(exact.root, "@app/alpha:test", exact.changed).single().arguments,
        )
    }

    @Test
    fun `Bun keeps the full package command for production files and unmanaged packages`() {
        val production = testFilesWorkspace("bun test", "alpha.test.ts")
            .also { File(it.root, "bun.lock").writeText("") }
        val source = production.fixture.source("alpha", "alpha.ts", "export const alpha = 1")
        val configured = testFilesWorkspace("bun test", "alpha.test.ts")
            .also { File(it.root, "bun.lock").writeText("") }
        File(configured.root, "bunfig.toml").writeText("[test]\npreload = [\"./setup.ts\"]\n")
        val npm = testFilesWorkspace("bun test", "alpha.test.ts")

        assertEquals(
            listOf("bun", "--filter", "@app/alpha", "test"),
            exactCommands(production.root, "@app/alpha:test", source).single().arguments,
        )
        assertEquals(
            listOf("bun", "--filter", "@app/alpha", "test"),
            exactCommands(configured.root, "@app/alpha:test", configured.changed).single().arguments,
        )
        assertFull(npm.fixture, npm.changed)
    }

    @Test
    fun `a single-package Bun project selects changed test files from its root`() {
        val root = createTempDirectory("bun-exact-root").toFile()
        File(root, "package.json").writeText("""{ "name": "app", "scripts": { "test": "bun test" } }""")
        File(root, "bun.lock").writeText("")
        val changed = File(root, "src/a.test.ts").apply {
            parentFile.mkdirs()
            writeText("")
        }

        val command = nodeCommands(root.path, listOf(".:test"), eligibleChanges(changed)).single()

        assertEquals(listOf("bun", "test", "./src/a.test.ts"), command.arguments)
    }

    private fun exactCommands(root: File, task: String, changed: File): List<CliCommand> =
        nodeCommands(
            root.path,
            listOf(task),
            eligibleChanges(changed),
        )

    private fun eligibleChanges(vararg files: File): BuildChanges {
        val paths = files.map { it.path }
        return BuildChanges(paths, paths.toSet(), comparedToBase = true)
    }

    private fun assertFull(fixture: Workspace, changed: File) {
        assertEquals(
            listOf("npm", "test", "--workspace", "@app/alpha"),
            exactCommands(fixture.root, "@app/alpha:test", changed).single().arguments,
        )
    }

    private fun testFilesWorkspace(script: String, testName: String = "alpha.test.js"): ExactWorkspace {
        val fixture = workspace()
        val changed = fixture.test("alpha", testName, "")
        fixture.packageManifest("alpha", script, "1.0.0", dependency = "left-pad")
        return ExactWorkspace(fixture, changed)
    }

    private fun exactJestWorkspace(
        transform: Boolean = false,
        extraSource: String? = null,
    ): ExactWorkspace {
        val fixture = workspace()
        val changed = fixture.source("alpha", "alpha.js", "export const alpha = 1")
        fixture.test("alpha", "alpha.test.js", "import { alpha } from './alpha.js'")
        extraSource?.let { fixture.source("alpha", "dynamic.js", it) }
        fixture.packageManifest("alpha", "jest", "^30.0.0", transform = transform)
        return ExactWorkspace(fixture, changed)
    }

    private fun workspace(): Workspace {
        val root = createTempDirectory("node-exact").toFile()
        File(root, "package.json").writeText(
            """{ "name": "root", "private": true, "workspaces": ["packages/*"] }""",
        )
        return Workspace(root)
    }

    private data class ExactWorkspace(val fixture: Workspace, val changed: File) {
        val root: File get() = fixture.root
    }

    private class Workspace(val root: File) {
        fun packageManifest(
            name: String,
            script: String,
            version: String,
            dependency: String = script.substringBefore(' '),
            transform: Boolean = false,
        ) {
            val transformDependency = if (transform) ", \"babel-jest\": \"^30.0.0\"" else ""
            File(root, "packages/$name/package.json").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    {
                      "name": "@app/$name",
                      "private": true,
                      "scripts": { "test": "$script" },
                      "devDependencies": { "$dependency": "$version"$transformDependency }
                    }
                    """.trimIndent(),
                )
            }
        }

        fun source(name: String, file: String, content: String): File =
            File(root, "packages/$name/src/$file").apply {
                parentFile.mkdirs()
                writeText(content)
            }

        fun test(name: String, file: String, content: String): File =
            File(root, "packages/$name/test/$file").apply {
                parentFile.mkdirs()
                writeText(content)
            }
    }
}
