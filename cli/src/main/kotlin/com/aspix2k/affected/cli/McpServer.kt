package com.aspix2k.affected.cli

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Path

internal class McpServer(
    private val directory: File,
    private val cache: Path,
    private val run: (List<String>, PrintStream) -> Int = { arguments, output ->
        execute(arguments, output, output, cache)
    },
) {

    fun serve(input: InputStream, output: PrintStream) {
        input.bufferedReader().lineSequence().filter(String::isNotBlank).forEach { line ->
            answer(line)?.let { output.print(GSON.toJson(it) + "\n") }
            output.flush()
        }
    }

    private fun answer(line: String): JsonObject? {
        val message = runCatching { JsonParser.parseString(line) }.getOrNull()
            ?: return failure(JsonNull.INSTANCE, PARSE_ERROR, "The message is not JSON")
        val request = message as? JsonObject
            ?: return failure(JsonNull.INSTANCE, INVALID_REQUEST, "The message is not a JSON object")
        val id = request.get("id")?.takeUnless(JsonElement::isJsonNull) ?: return null
        return respond(id, text(request.get("method")), request.get("params") as? JsonObject ?: JsonObject())
    }

    private fun respond(id: JsonElement, method: String?, parameters: JsonObject): JsonObject = when (method) {
        null -> failure(id, INVALID_REQUEST, "The request names no method")
        "initialize" -> success(id, initialize(parameters))
        "ping" -> success(id, JsonObject())
        "tools/list" -> success(id, JsonObject().apply { add("tools", tools()) })
        "tools/call" -> TOOLS.firstOrNull { it.name == text(parameters.get("name")) }
            ?.let { success(id, call(it, parameters.get("arguments") as? JsonObject ?: JsonObject())) }
            ?: failure(id, INVALID_PARAMS, "Unknown tool")
        else -> failure(id, METHOD_NOT_FOUND, "Method not found")
    }

    private fun initialize(parameters: JsonObject) = JsonObject().apply {
        val requested = text(parameters.get("protocolVersion"))
        addProperty("protocolVersion", requested.takeIf { it in PROTOCOLS } ?: PROTOCOLS[0])
        add("capabilities", JsonObject().apply { add("tools", JsonObject()) })
        add(
            "serverInfo",
            JsonObject().apply {
                addProperty("name", "affected")
                addProperty("version", McpServer::class.java.`package`?.implementationVersion ?: "development")
            },
        )
    }

    private fun call(tool: Tool, arguments: JsonObject): JsonObject {
        val commandLine = runCatching {
            val base = arguments.get("base").asString.also { require(!it.startsWith("-")) }
            listOf(tool.command, "--base", base, "--dir", directory.path) +
                arguments.getAsJsonArray("if_changed")?.flatMap { listOf("--if-changed", it.asString) }.orEmpty() +
                listOf("dependents", "consumers").filter { arguments.get(it)?.asBoolean == true }.map { "--$it" }
        }.getOrNull() ?: return result(WRONG_ARGUMENTS, failed = true)
        val captured = ByteArrayOutputStream()
        val code = runCatching { PrintStream(captured, true).use { run(commandLine, it) } }
            .getOrElse { return result("$captured\nAffected stopped: $it", failed = true) }
        val output = captured.toString()
        val omitted = (output.length - MAX_TEXT).coerceAtLeast(0)
        val notice = if (omitted > 0) "[$omitted earlier characters omitted]\n" else ""
        return result(
            "$notice${output.takeLast(MAX_TEXT)}\nResult: ${meaning(tool, code)}",
            failed = code != EXIT_PASSED && code != EXIT_FAILED,
        )
    }

    private fun result(text: String, failed: Boolean) = JsonObject().apply {
        val content = JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", text)
        }
        add("content", JsonArray().apply { add(content) })
        addProperty("isError", failed)
    }

    private fun meaning(tool: Tool, code: Int): String = when {
        code == EXIT_PASSED && tool.command == "plan" -> "planned — nothing was run."
        code == EXIT_PASSED -> "passed — every planned check passed, or nothing needed to run."
        code == EXIT_FAILED -> "failed — a planned check failed."
        code == EXIT_BLOCKED -> "blocked — success cannot be claimed, for the reason given above."
        else -> "wrong arguments."
    }

    private fun tools() = JsonArray().apply {
        TOOLS.forEach { tool ->
            add(
                JsonObject().apply {
                    addProperty("name", tool.name)
                    addProperty("description", tool.description)
                    add("inputSchema", JsonParser.parseString(SCHEMA))
                    add("annotations", JsonParser.parseString(ANNOTATIONS))
                },
            )
        }
    }

    private fun success(id: JsonElement, result: JsonObject) = message(id).apply { add("result", result) }

    private fun failure(id: JsonElement, code: Int, text: String) = message(id).apply {
        add(
            "error",
            JsonObject().apply {
                addProperty("code", code)
                addProperty("message", text)
            },
        )
    }

    private fun message(id: JsonElement) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id)
    }

    private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.asString

    private class Tool(val name: String, val command: String, val description: String)

    private companion object {
        val GSON = GsonBuilder().disableHtmlEscaping().create()
        val PROTOCOLS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        val TOOLS = listOf(
            Tool(
                "affected_plan",
                "plan",
                "Lists the test and build tasks that the changes of this repository against a base branch need. " +
                    "Runs no test. With if_changed it answers which checks cover a file before the file is edited.",
            ),
            Tool(
                "affected_run",
                "run",
                "Runs the test and build tasks that the changes of this repository against a base branch need " +
                    "and returns their output. It answers when they have finished. " +
                    "Only the result 'passed' means the change is verified.",
            ),
        )
        const val SCHEMA = """{
          "type": "object",
          "properties": {
            "base": {"type": "string", "description": "Branch to compare with, for example main"},
            "if_changed": {
              "type": "array",
              "items": {"type": "string"},
              "description": "Existing files, relative to the repository, to treat as changed too"
            },
            "dependents": {"type": "boolean", "description": "Also test modules that depend on the changed ones"},
            "consumers": {"type": "boolean", "description": "Also compile direct consumers of changed API"}
          },
          "required": ["base"]
        }"""
        const val ANNOTATIONS = """{"destructiveHint": false, "openWorldHint": false}"""
        const val WRONG_ARGUMENTS = "base must be the name of a branch, if_changed a list of paths, " +
            "dependents and consumers true or false."
        const val MAX_TEXT = 40_000
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
    }
}
