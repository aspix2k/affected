package com.aspix2k.affected.cli

import com.aspix2k.affected.Engine
import com.aspix2k.affected.EnginePlan
import com.aspix2k.affected.isProjectDocumentation
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

internal class BrokenFileAudit(
    private val arguments: Arguments,
    private val out: PrintStream,
    private val err: PrintStream,
    private val cache: Path,
) {

    fun run(): Int {
        if (!isClean()) {
            err.println("Affected audit: breaking files needs a git working tree without local changes.")
            return EXIT_USAGE
        }
        val files = files() ?: return EXIT_USAGE
        val clean = plan()
        clean.blocker?.let { blocker ->
            err.println(explain(blocker, arguments, clean))
            return EXIT_BLOCKED
        }
        if (!runBlocking { Engine.run(clean) && Engine.runEveryTest(clean) }) {
            err.println("Affected audit: the tests fail before any file is broken, so nothing can be compared.")
            return EXIT_FAILED
        }
        var missed = 0
        for (file in files) {
            val verdict = verdict(file)
            out.println("$verdict ${relative(file)}")
            if (verdict == MISSED) missed++
            if (!isClean()) {
                err.println("Affected audit: the run changed files of the working tree, so it stops here.")
                return EXIT_FAILED
            }
        }
        out.println("\nAffected audit: broke ${files.size} files, the planned checks missed $missed.")
        return if (missed == 0) EXIT_PASSED else EXIT_MISSED
    }

    private fun verdict(file: File): String {
        val original = file.readBytes()
        val restore = Thread { file.writeBytes(original) }
        Runtime.getRuntime().addShutdownHook(restore)
        try {
            file.writeText(BROKEN)
            val plan = plan()
            return runBlocking {
                when {
                    plan.blocker != null -> REFUSED
                    !Engine.run(plan) -> CAUGHT
                    Engine.runEveryTest(plan) || Engine.runEveryTest(plan) -> UNNOTICED
                    else -> MISSED
                }
            }
        } finally {
            restore.run()
            Runtime.getRuntime().removeShutdownHook(restore)
        }
    }

    private fun isClean(): Boolean = git("status", "--porcelain")?.isBlank() == true

    private fun plan(): EnginePlan = Engine.plan(arguments.request(cache) { _, _ -> })

    private fun files(): List<File>? {
        val tracked = git("ls-files", "-z").orEmpty().split('\u0000')
            .filter(String::isNotEmpty)
            .map { File(arguments.directory, it) }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
        val named = arguments.broken.map { File(it).takeIf(File::isAbsolute) ?: File(arguments.directory, it) }
            .map(File::normalize)
        named.firstOrNull { it !in tracked }?.let {
            err.println("Affected audit: $it is not a file that git tracks in ${arguments.directory}.")
            return null
        }
        val sampled = tracked.filterNot { it in named || isProjectDocumentation(relative(it)) }.shuffled()
        return (named + sampled.take(arguments.sample)).distinct()
    }

    private fun relative(file: File): String = file.relativeToOrSelf(arguments.directory).invariantSeparatorsPath

    private fun git(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + command).directory(arguments.directory)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = process.inputStream.bufferedReader().readText()
        output.takeIf { process.waitFor() == 0 }
    }.getOrNull()

    private companion object {
        const val BROKEN = "<<<<<<< Affected audit broke this file on purpose\n"
        const val REFUSED = "refused  "
        const val CAUGHT = "caught   "
        const val UNNOTICED = "unnoticed"
        const val MISSED = "MISSED   "
    }
}
