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
    fun `ambiguous or foreign roots stay off the Deno adapter`() {
        val both = denoRoot("{}")
        File(both, "deno.jsonc").writeText("{}")
        val node = denoRoot("{}")
        File(node, "package.json").writeText("{}")
        val invalid = denoRoot("not json")
        val empty = createTempDirectory("deno-none").toFile()

        assertNull(denoConfig(both))
        assertNull(denoConfig(node))
        assertNull(denoConfig(invalid))
        assertNull(denoProjectRoot(empty))
    }

    private fun denoRoot(config: String, name: String = "deno.json"): File {
        val root = createTempDirectory("deno-root").toFile()
        File(root, name).writeText(config)
        return root
    }
}
