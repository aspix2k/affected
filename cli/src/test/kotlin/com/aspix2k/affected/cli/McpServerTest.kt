package com.aspix2k.affected.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpServerTest {

    @Test
    fun `the handshake names the server and keeps a protocol version it knows`() {
        val (known, unknown) = exchange(
            repository(),
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}""",
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            """{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""",
        )

        assertEquals("2024-11-05", known.result().get("protocolVersion").asString)
        assertEquals("affected", known.result().getAsJsonObject("serverInfo").get("name").asString)
        assertTrue(known.result().getAsJsonObject("capabilities").has("tools"))
        assertEquals("2025-06-18", unknown.result().get("protocolVersion").asString)
    }

    @Test
    fun `both tools are listed with the base branch as their only required argument`() {
        val tools = exchange(repository(), """{"jsonrpc":"2.0","id":"a","method":"tools/list"}""").single()
            .result().getAsJsonArray("tools").map { it.asJsonObject }

        assertEquals(listOf("affected_plan", "affected_run"), tools.map { it.get("name").asString })
        tools.forEach { tool ->
            assertEquals("""["base"]""", tool.getAsJsonObject("inputSchema").get("required").toString())
            assertEquals("""{"destructiveHint":false,"openWorldHint":false}""", tool.get("annotations").toString())
        }
    }

    @Test
    fun `the plan of a file that has not changed is answered through the tool`() {
        val root = repository()

        val text = call(root, "affected_plan", """{"base":"main","if_changed":["index.js"]}""")

        assertTrue("Assumed to have changed: 1" in text, text)
        assertTrue(Regex("NODE in \\.: \\S*:test").containsMatchIn(text), text)
        assertTrue(text.endsWith("Result: planned — nothing was run."), text)
    }

    @Test
    fun `a run returns the output of the tests and says that a check failed`() {
        val root = repository()
        File(root, "broken.test.js").writeText("require('node:test')('fails', () => { throw new Error('broken') })\n")
        git(root, "add", ".")

        val text = call(root, "affected_run", """{"base":"main"}""")

        assertTrue("broken" in text, text)
        assertTrue(text.endsWith("Result: failed — a planned check failed."), text)
    }

    @Test
    fun `a run of a branch that breaks nothing is reported as passed`() {
        val root = repository()
        File(root, "fine.test.js").writeText("require('node:test')('passes', () => {})\n")
        git(root, "add", ".")

        val text = call(root, "affected_run", """{"base":"main"}""")

        assertTrue(text.endsWith("Result: passed — every planned check passed, or nothing needed to run."), text)
    }

    @Test
    fun `a branch that cannot be compared with is explained and flagged as an error`() {
        val result = exchange(repository(), request("affected_plan", """{"base":"release"}""")).single().result()

        assertTrue(result.get("isError").asBoolean)
        assertTrue("cannot compare with 'release'" in text(result), text(result))
        assertTrue(text(result).endsWith("Result: blocked — success cannot be claimed, for the reason given above."))
    }

    @Test
    fun `a file outside the repository is a tool error`() {
        val result = exchange(repository(), request("affected_plan", """{"base":"main","if_changed":["../x.js"]}"""))
            .single().result()

        assertTrue(result.get("isError").asBoolean)
        assertTrue("must be a file inside" in text(result), text(result))
    }

    @Test
    fun `arguments of the wrong shape and a base that reads as an option are tool errors`() {
        val results = exchange(
            repository(),
            request("affected_plan", """{"if_changed":["index.js"]}"""),
            request("affected_plan", """{"base":"main","if_changed":"index.js"}"""),
            request("affected_plan", """{"base":"--all"}"""),
            request("affected_plan", """[]"""),
        ).map { it.result() }

        assertEquals(4, results.size)
        results.forEach { result ->
            assertTrue(result.get("isError").asBoolean)
            assertTrue(text(result).startsWith("base must be the name of a branch"), text(result))
        }
    }

    @Test
    fun `the switches reach the command and its failure does not stop the server`() {
        val seen = ArrayList<List<String>>()
        val server = McpServer(File("/repository"), createTempDirectory("mcp-cache")) { arguments, _ ->
            seen += arguments
            error("no git here")
        }

        val responses = exchange(
            server,
            request("affected_run", """{"base":"main","dependents":true,"consumers":true,"if_changed":["a.kt"]}"""),
            """{"jsonrpc":"2.0","id":2,"method":"ping"}""",
        )

        val expected = "run --base main --dir /repository --if-changed a.kt --dependents --consumers"
        assertEquals(listOf(expected.split(' ')), seen)
        assertTrue(responses[0].result().get("isError").asBoolean)
        assertTrue("Affected stopped: java.lang.IllegalStateException: no git here" in text(responses[0].result()))
        assertEquals(JsonObject(), responses[1].result())
    }

    @Test
    fun `long output keeps its end and says how much was left out`() {
        val server = McpServer(File("/repository"), createTempDirectory("mcp-cache")) { _, output ->
            output.print("start" + "x".repeat(40_000) + "end")
            EXIT_FAILED
        }

        val text = text(exchange(server, request("affected_run", """{"base":"main"}""")).single().result())

        assertTrue(text.startsWith("[8 earlier characters omitted]\n" + "x".repeat(100)), text.take(60))
        assertTrue(text.endsWith("xend\nResult: failed — a planned check failed."), text.takeLast(60))
    }

    @Test
    fun `an unknown tool or method and a message that is no request are protocol errors the server survives`() {
        val responses = exchange(
            repository(),
            request("affected_audit", """{"base":"main"}"""),
            """{"jsonrpc":"2.0","id":4,"method":"resources/list"}""",
            "{",
            "[]",
            """{"jsonrpc":"2.0","id":"five"}""",
            """{"jsonrpc":"2.0","id":"six","method":"ping"}""",
        )

        assertEquals(
            listOf(-32602, -32601, -32700, -32600, -32600),
            responses.dropLast(1).map { it.getAsJsonObject("error").get("code").asInt },
        )
        assertEquals("""{"jsonrpc":"2.0","id":"six","result":{}}""", responses.last().toString())
    }

    @Test
    fun `a notification and a blank line get no answer, and a ping gets an empty one`() {
        val responses = exchange(
            repository(),
            """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}""",
            "",
            """{"jsonrpc":"2.0","id":7,"method":"ping"}""",
        )

        assertEquals("""{"jsonrpc":"2.0","id":7,"result":{}}""", responses.single().toString())
    }

    @Test
    fun `the server needs an existing directory and takes no other argument`() {
        val errors = ByteArrayOutputStream()
        val nowhere = PrintStream(ByteArrayOutputStream())
        val cache = createTempDirectory("mcp-cache")

        val codes = listOf(listOf("--dir", "/nonexistent/affected"), listOf("--base", "main"), listOf("--dir")).map {
            serve(it, "".byteInputStream(), nowhere, PrintStream(errors), cache)
        }

        assertEquals(listOf(EXIT_USAGE, EXIT_USAGE, EXIT_USAGE), codes)
        assertTrue("affected mcp [--dir <path>]" in errors.toString())
    }

    private fun call(root: File, tool: String, arguments: String): String {
        val result = exchange(root, request(tool, arguments)).single().result()
        assertFalse(result.get("isError").asBoolean, result.toString())
        return text(result)
    }

    private fun text(result: JsonObject): String =
        result.getAsJsonArray("content").single().asJsonObject.get("text").asString

    private fun request(tool: String, arguments: String): String =
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}"""

    private fun exchange(root: File, vararg lines: String): List<JsonObject> =
        exchange(McpServer(root, createTempDirectory("mcp-cache")), *lines)

    private fun exchange(server: McpServer, vararg lines: String): List<JsonObject> {
        val out = ByteArrayOutputStream()
        val input = lines.joinToString("\n", postfix = "\n").byteInputStream()
        server.serve(input, PrintStream(out, false, Charsets.UTF_8))
        return out.toString(Charsets.UTF_8).lines().filter(String::isNotEmpty)
            .map { JsonParser.parseString(it).asJsonObject }
    }

    private fun JsonObject.result(): JsonObject = getAsJsonObject("result")

    private fun repository(): File {
        val root = createTempDirectory("mcp-repository").toFile().canonicalFile
        File(root, "package.json").writeText("""{"name":"demo","version":"1.0.0","scripts":{"test":"node --test"}}""")
        File(root, "index.js").writeText("module.exports = 1\n")
        git(root, "init", "--quiet", "--initial-branch=main")
        git(root, "add", ".")
        git(root, "-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-m", "init")
        git(root, "checkout", "--quiet", "-b", "feature")
        return root
    }

    private fun git(root: File, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git") + arguments).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }
}
