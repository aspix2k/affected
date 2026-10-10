package com.aspix2k.affected

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files

data class DeclaredDependency(val path: String, val system: String, val root: String, val module: String)

data class DeclaredOwner(val system: String, val root: String, val module: String)

object DeclaredDependencies {

    const val LOCATION = ".affected/dependencies.json"

    fun read(directory: File): List<DeclaredDependency>? {
        val file = File(directory, LOCATION)
        if (Files.isSymbolicLink(file.parentFile.toPath())) return null
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return emptyList()
        return runCatching {
            require(isPlain(file) && file.length() <= MAX_BYTES)
            val document = JsonParser.parseString(file.readText()).asJsonObject
            require(document.get("version").asInt == VERSION)
            document.getAsJsonArray("dependencies").map { element ->
                val entry = element.asJsonObject
                DeclaredDependency(
                    entry.text("path").also { require(isRepositoryPath(it)) },
                    entry.text("system"),
                    entry.text("root").also { require(it == ROOT || isRepositoryPath(it)) },
                    entry.text("module"),
                )
            }
        }.getOrNull()
    }

    fun write(directory: File, dependencies: Collection<DeclaredDependency>) {
        val file = File(directory, LOCATION)
        require(!Files.isSymbolicLink(file.parentFile.toPath()) && (!file.exists() || isPlain(file))) {
            "$LOCATION must be a regular file in a regular directory"
        }
        val document = JsonObject()
        document.addProperty("version", VERSION)
        document.add(
            "dependencies",
            dependencies.distinct().sortedWith(compareBy({ it.path }, { it.system }, { it.root }, { it.module }))
                .fold(JsonArray()) { array, dependency ->
                    array.apply {
                        add(
                            JsonObject().apply {
                                addProperty("path", dependency.path)
                                addProperty("system", dependency.system)
                                addProperty("root", dependency.root)
                                addProperty("module", dependency.module)
                            },
                        )
                    }
                },
        )
        file.apply { parentFile.mkdirs() }
            .writeText(GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(document) + "\n")
    }

    internal fun owners(directory: File, dependencies: List<DeclaredDependency>, file: File): List<DeclaredOwner> {
        val path = runCatching { file.relativeTo(directory).invariantSeparatorsPath }.getOrNull()
        return dependencies.filter { it.path == path }.map { dependency ->
            val root = if (dependency.root == ROOT) directory else File(directory, dependency.root)
            DeclaredOwner(dependency.system, root.invariantSeparatorsPath, dependency.module)
        }
    }

    fun relativeRoot(directory: File, root: String): String? =
        runCatching { File(root).relativeTo(directory).invariantSeparatorsPath.ifEmpty { ROOT } }.getOrNull()
            ?.takeIf { it == ROOT || isRepositoryPath(it) }

    private fun isPlain(file: File): Boolean =
        file.isFile && !Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(file.parentFile.toPath())

    private fun JsonObject.text(name: String): String = get(name).asString.also { require(it.isNotBlank()) }

    private fun isRepositoryPath(path: String): Boolean =
        !File(path).isAbsolute && path.split('/').none { it.isEmpty() || it == "." || it == ".." } && '\\' !in path

    private const val VERSION = 1
    private const val MAX_BYTES = 1024 * 1024
    private const val ROOT = "."
}
