package com.aspix2k.affected

import java.io.File
import java.util.concurrent.TimeUnit

object FixtureRepository {

    /** Tests run from the module directory, while fixtures live at the repository root. */
    val root: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "fixtures") }
        .firstOrNull { it.isDirectory }
        ?: File(System.getProperty("user.dir"), "fixtures")

    fun available(name: String): Boolean = File(root, "$name/.git").isDirectory

    fun names(): List<String> = root.listFiles()
        ?.filter { File(it, ".git").isDirectory }
        ?.map { it.name }
        ?.sorted()
        .orEmpty()

    /**
     * Copies a fixture into a scratch clone so a test can commit, branch and edit
     * without touching the checkout that took a network round trip to fetch.
     */
    fun checkout(name: String): File {
        val source = File(root, name)
        val target = File.createTempFile("affected-$name", "").apply {
            delete()
            mkdirs()
        }

        git(target.parentFile, "clone", "--quiet", "--no-hardlinks", source.path, target.path)
        git(target, "config", "user.email", "fixture@example.com")
        git(target, "config", "user.name", "fixture")
        return target
    }

    fun git(directory: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(120, TimeUnit.SECONDS)
        return output
    }

    fun sourcesIn(directory: File, extension: String, limit: Int = 50): List<File> =
        directory.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "build" && it.name != "node_modules" }
            .filter { it.isFile && it.extension == extension }
            .take(limit)
            .toList()
}
