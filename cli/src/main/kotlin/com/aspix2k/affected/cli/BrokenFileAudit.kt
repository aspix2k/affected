package com.aspix2k.affected.cli

import com.aspix2k.affected.DeclaredDependencies
import com.aspix2k.affected.DeclaredDependency
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
        val declared = DeclaredDependencies.read(arguments.directory)
        if (arguments.audit.learn && declared == null) {
            err.println("Affected audit: ${DeclaredDependencies.LOCATION} cannot be read, so nothing is added to it.")
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
        return audit(files, declared.orEmpty())
    }

    private fun audit(files: List<File>, declared: List<DeclaredDependency>): Int {
        val missed = ArrayList<DeclaredDependency>()
        val verdicts = LinkedHashMap<String, String>()
        for (file in files) {
            val verdict = verdict(file, missed)
            verdicts[relative(file)] = verdict.trim().lowercase()
            out.println("$verdict ${relative(file)}")
            if (!isClean()) {
                err.println("Affected audit: the run changed files of the working tree, so it stops here.")
                return EXIT_FAILED
            }
        }
        val paths = missed.distinctBy { it.path }.size
        out.println("\nAffected audit: broke ${files.size} files, the planned checks missed $paths.")
        missed.forEach { out.println("  ${it.path} is needed by ${it.system} ${it.module} in ${it.root}") }
        if (arguments.audit.learn && missed.isNotEmpty()) {
            DeclaredDependencies.write(arguments.directory, declared + missed)
            out.println("Declared in ${DeclaredDependencies.LOCATION}; commit it to keep these tests planned.")
        }
        arguments.audit.report?.let { writeBrokenFilesReport(it, arguments.baseBranch, verdicts, missed) }
        return if (missed.isEmpty()) EXIT_PASSED else EXIT_MISSED
    }

    private fun verdict(file: File, missed: MutableList<DeclaredDependency>): String {
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
                    Engine.runEveryTest(plan) -> UNNOTICED
                    else -> Engine.failedTestModules(plan).mapNotNull { module ->
                        DeclaredDependencies.relativeRoot(arguments.directory, module.executionRoot)
                            ?.let { DeclaredDependency(relative(file), module.systemId, it, module.executionId) }
                    }.also(missed::addAll).let { if (it.isEmpty()) UNNOTICED else MISSED }
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
        val named = arguments.audit.broken.map { File(it).takeIf(File::isAbsolute) ?: File(arguments.directory, it) }
            .map(File::normalize)
        named.firstOrNull { it !in tracked }?.let {
            err.println("Affected audit: $it is not a file that git tracks in ${arguments.directory}.")
            return null
        }
        val sampled = tracked.filterNot { it in named || isProjectDocumentation(relative(it)) }.shuffled()
        return (named + sampled.take(arguments.audit.sample)).distinct()
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
