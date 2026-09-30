package com.aspix2k.affected.build.deno

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DenoCommandTest {

    @Test
    fun `a Deno root runs one deno test command`() {
        val root = denoRoot("{}")

        assertEquals(listOf("deno", "test"), denoCommands(root, listOf(".:test")).single().arguments)
        assertEquals(emptyList(), denoCommands(root, emptyList()))
    }

    @Test
    fun `a declared test task keeps the project command`() {
        val root = denoRoot("""{ "tasks": { "test": "deno test -A" } }""")

        assertEquals(listOf("deno", "task", "test"), denoCommands(root, listOf(".:test")).single().arguments)
    }

    @Test
    fun `a commented deno jsonc workspace is one runnable root module`() {
        val root = denoRoot("{\n // members\n \"workspace\": [\"./a\"]\n}", "deno.jsonc")
        File(root, "a").mkdirs()
        File(root, "a/deno.json").writeText("{}")
        File(root, "a/mod_test.ts").writeText("")

        val module = denoRootModule(root)

        assertTrue(module.hasTests)
        assertEquals(".", module.executionId)
        assertEquals(listOf(root.invariantSeparatorsPath), module.contentRoots)
    }

    @Test
    fun `test file conventions and test configuration make the root runnable`() {
        listOf("mod_test.ts", "mod.test.tsx", "test.js", "nested/a_test.mjs", "member/deno.json").forEach { name ->
            val root = denoRoot("{}")
            File(root, name).apply {
                parentFile.mkdirs()
                writeText("")
            }
            assertTrue(denoRootModule(root).hasTests, name)
        }
        assertTrue(denoRootModule(denoRoot("""{ "test": { "include": ["spec/"] } }""")).hasTests)
        assertFalse(denoRootModule(denoRoot("{}")).hasTests)
    }

    @Test
    fun `an unparseable or ambiguous config still makes a fully running Deno root`() {
        val trailing = denoRoot("{ \"tasks\": { \"test\": \"deno test\", }, }")
        val both = denoRoot("{}")
        File(both, "deno.jsonc").writeText("{}")

        listOf(denoRoot("not json"), trailing, both).forEach { root ->
            assertEquals(root, denoProjectRoot(root))
            assertNull(denoConfig(root)?.json)
            assertTrue(denoRootModule(root).hasTests)
            assertEquals(listOf("deno", "test"), denoCommands(root, listOf(".:test")).single().arguments)
        }
    }

    @Test
    fun `a package json without a test script leaves the root to Deno`() {
        val plain = denoRoot("{}")
        File(plain, "package.json").writeText("""{ "scripts": { "build": "tsc" } }""")
        val bare = denoRoot("{}")
        File(bare, "package.json").writeText("{}")

        assertEquals(plain, denoProjectRoot(plain))
        assertEquals(bare, denoProjectRoot(bare))
    }

    @Test
    fun `a package json that can run tests keeps the root with Node`() {
        val script = denoRoot("{}")
        File(script, "package.json").writeText("""{ "scripts": { "test": "jest" } }""")
        val workspaces = denoRoot("{}")
        File(workspaces, "package.json").writeText("""{ "workspaces": ["packages/*"] }""")
        val invalid = denoRoot("{}")
        File(invalid, "package.json").writeText("not json")
        val empty = createTempDirectory("deno-none").toFile()

        listOf(script, workspaces, invalid).forEach { root -> assertNull(denoProjectRoot(root)) }
        assertNull(denoConfig(empty))
        assertNull(denoProjectRoot(empty))
    }

    private fun denoRoot(config: String, name: String = "deno.json"): File {
        val root = createTempDirectory("deno-root").toFile()
        File(root, name).writeText(config)
        return root
    }
}
