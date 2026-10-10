package com.aspix2k.affected.cli

import com.aspix2k.affected.Engine
import com.aspix2k.affected.EngineAudit
import com.aspix2k.affected.EngineBlocker
import com.aspix2k.affected.EnginePlan
import com.aspix2k.affected.EngineRequest
import com.aspix2k.affected.TaskGroup
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.PrintStream
import java.nio.file.Path
import kotlin.system.exitProcess

internal const val EXIT_PASSED = 0
internal const val EXIT_FAILED = 1
internal const val EXIT_BLOCKED = 2
internal const val EXIT_MISSED = 3
internal const val EXIT_USAGE = 64

internal class Arguments(
    val command: String,
    val directory: File,
    val baseBranch: String,
    val testDependents: Boolean,
    val checkConsumers: Boolean,
    val stopAfterFirstFailure: Boolean,
)

internal const val USAGE = """Usage: affected <plan|run|audit> --base <branch> [options]

  plan                 print the checks the changes against the base branch need
  run                  run them and exit with 0 only when all passed
  audit                run them, then every test, and exit with 3 when a test
                       fails that the planned checks did not cover

  --base <branch>      branch to compare with (required)
  --dir <path>         repository directory (default: current directory)
  --dependents         also test modules that depend on the changed ones
  --consumers          also compile direct consumers of changed API
  --fail-fast          stop after the first failed group (not with audit)
"""

internal fun parse(arguments: List<String>): Arguments? {
    val command = arguments.firstOrNull()?.takeIf { it in setOf("plan", "run", "audit") } ?: return null
    var directory = File("").absoluteFile
    var baseBranch: String? = null
    val flags = HashSet<String>()
    val rest = arguments.drop(1).iterator()
    while (rest.hasNext()) {
        when (val argument = rest.next()) {
            "--base" -> baseBranch = if (rest.hasNext()) rest.next() else return null
            "--dir" -> directory = if (rest.hasNext()) File(rest.next()).absoluteFile.normalize() else return null
            "--dependents", "--consumers", "--fail-fast" -> flags += argument
            else -> return null
        }
    }
    if (command == "audit" && "--fail-fast" in flags) return null
    return Arguments(
        command,
        directory,
        baseBranch?.takeIf(String::isNotBlank) ?: return null,
        "--dependents" in flags,
        "--consumers" in flags,
        "--fail-fast" in flags,
    )
}

internal fun execute(arguments: List<String>, out: PrintStream, err: PrintStream, cache: Path): Int {
    val parsed = parse(arguments) ?: return EXIT_USAGE.also { err.print(USAGE) }
    if (!parsed.directory.isDirectory) return EXIT_USAGE.also { err.println("Not a directory: ${parsed.directory}") }
    val plan = Engine.plan(
        EngineRequest(
            parsed.directory,
            parsed.baseBranch,
            cache,
            parsed.testDependents,
            parsed.checkConsumers,
            parsed.stopAfterFirstFailure,
        ) { text, error -> (if (error) err else out).print(text) },
    )
    describe(plan, parsed, out)
    val blocker = plan.blocker
    if (blocker != null) err.println(explain(blocker, parsed, plan))
    val runnable = blocker == null || blocker == EngineBlocker.UNRESOLVED_CHANGES
    if (!runnable || parsed.command == "plan") return exitCode(passed = true, blocker)
    if (parsed.command == "audit") return audit(plan, parsed, out, blocker)
    if (plan.plan.isEmpty) return exitCode(passed = true, blocker)
    val passed = runBlocking { Engine.run(plan) }
    out.println(if (passed) "\nAffected: all planned checks passed." else "\nAffected: checks failed.")
    return exitCode(passed, blocker)
}

private fun audit(plan: EnginePlan, arguments: Arguments, out: PrintStream, blocker: EngineBlocker?): Int {
    val audit = runBlocking { Engine.audit(plan) }
    out.println("\n" + summary(plan, audit))
    audit.missed.forEach { out.println("  ${describe(it, arguments)}") }
    return if (audit.missed.isEmpty()) exitCode(audit.failedInSelection.isEmpty(), blocker) else EXIT_MISSED
}

private fun summary(plan: EnginePlan, audit: EngineAudit): String {
    val planned = plan.plan.groups.sumOf { it.tasks.size }
    val every = plan.everyTest.groups.sumOf { it.tasks.size }
    val scope = "the planned checks ran $planned tasks, the full run $every"
    return when {
        every == 0 -> "Affected audit: no test task was found, so nothing was compared."
        audit.missed.isNotEmpty() -> "Affected audit: $scope. Failed only in the full run:"
        audit.failedInFullRun.isNotEmpty() -> "Affected audit: $scope. Every failure of the full run was planned."
        else -> "Affected audit: $scope. The full run passed."
    }
}

private fun describe(group: TaskGroup, arguments: Arguments): String {
    val root = File(group.root).relativeToOrSelf(arguments.directory).invariantSeparatorsPath.ifEmpty { "." }
    return "${group.systemId} in $root: ${group.tasks.joinToString(" ")}"
}

private fun exitCode(passed: Boolean, blocker: EngineBlocker?): Int = when {
    !passed -> EXIT_FAILED
    blocker != null -> EXIT_BLOCKED
    else -> EXIT_PASSED
}

private fun describe(plan: EnginePlan, arguments: Arguments, out: PrintStream) {
    val systems = plan.systems.joinToString { "${it.id} (${it.modules})" }.ifEmpty { "none" }
    out.println("Build systems: $systems")
    out.println("Changed files against ${arguments.baseBranch}: ${plan.changedFiles.size}")
    if (plan.plan.isEmpty) {
        out.println("Nothing to run.")
        return
    }
    plan.plan.groups.forEach { out.println(describe(it, arguments)) }
}

private fun explain(blocker: EngineBlocker, arguments: Arguments, plan: EnginePlan): String = when (blocker) {
    EngineBlocker.NOT_A_GIT_REPOSITORY ->
        "Affected: ${arguments.directory} is not a usable git repository, so the changes cannot be determined."
    EngineBlocker.NO_COMPARISON_BASE ->
        "Affected: cannot compare with '${arguments.baseBranch}'. " +
            "Fetch the branch with enough history (for example `git fetch origin ${arguments.baseBranch}`)."
    EngineBlocker.UNSUPPORTED_BUILD_SYSTEM ->
        "Affected: this repository has a Gradle or Maven build outside its root build, " +
            "which the command line cannot analyse yet; use the IDE plugin for it."
    EngineBlocker.NO_BUILD_SYSTEM ->
        "Affected: no supported build system was found in ${arguments.directory}, so nothing can be verified."
    EngineBlocker.UNRESOLVED_CHANGES ->
        "Affected: no check covers these changed files, so success cannot be claimed:\n" +
            plan.unresolved.joinToString("\n") { file ->
                "  ${file.relativeToOrSelf(arguments.directory).invariantSeparatorsPath}"
            }
}

fun main(arguments: Array<String>) {
    val cache = Path.of(System.getProperty("user.home"), ".cache", "affected")
    exitProcess(execute(arguments.asList(), System.out, System.err, cache))
}
