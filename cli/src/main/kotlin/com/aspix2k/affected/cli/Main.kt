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
    val audit: AuditOptions = AuditOptions(),
)

internal class AuditOptions(
    val broken: List<String> = emptyList(),
    val sample: Int = 0,
    val learn: Boolean = false,
    val report: File? = null,
) {
    val breaksFiles: Boolean get() = broken.isNotEmpty() || sample > 0

    val isSet: Boolean get() = breaksFiles || learn || report != null
}

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
  --break <path>       audit only: break this file on purpose and check that the
                       planned checks notice whenever any test does; repeatable
  --sample <count>     audit only: break that many tracked files picked at random
  --learn              with --break or --sample: declare every missed file as a
                       dependency of the tests that need it
  --report <path>      audit only: also write the result as JSON
"""

internal fun parse(arguments: List<String>): Arguments? {
    val command = arguments.firstOrNull()?.takeIf { it in setOf("plan", "run", "audit") } ?: return null
    val flags = HashSet<String>()
    val values = HashMap<String, MutableList<String>>()
    val rest = arguments.drop(1).iterator()
    while (rest.hasNext()) {
        when (val argument = rest.next()) {
            "--dependents", "--consumers", "--fail-fast", "--learn" -> flags += argument
            "--base", "--dir", "--break", "--sample", "--report" ->
                values.getOrPut(argument, ::mutableListOf) += if (rest.hasNext()) rest.next() else return null
            else -> return null
        }
    }
    val audit = auditOptions(flags, values) ?: return null
    val parsed = Arguments(
        command,
        values["--dir"]?.last()?.let { File(it).absoluteFile.normalize() } ?: File("").absoluteFile,
        values["--base"]?.last()?.takeIf(String::isNotBlank) ?: return null,
        "--dependents" in flags,
        "--consumers" in flags,
        "--fail-fast" in flags,
        audit,
    )
    return parsed.takeUnless { it.mixesCommands() }
}

private fun auditOptions(flags: Set<String>, values: Map<String, List<String>>): AuditOptions? {
    val sample = values["--sample"]?.last()?.let { it.toIntOrNull()?.takeIf { count -> count > 0 } ?: return null }
    return AuditOptions(
        values["--break"].orEmpty(),
        sample ?: 0,
        "--learn" in flags,
        values["--report"]?.last()?.let { File(it).absoluteFile.normalize() },
    )
}

private fun Arguments.mixesCommands(): Boolean =
    if (command == "audit") stopAfterFirstFailure || audit.learn && !audit.breaksFiles else audit.isSet

internal fun execute(arguments: List<String>, out: PrintStream, err: PrintStream, cache: Path): Int {
    val parsed = parse(arguments) ?: return EXIT_USAGE.also { err.print(USAGE) }
    misplaced(parsed)?.let { return EXIT_USAGE.also { _ -> err.println(it) } }
    if (parsed.audit.breaksFiles) return BrokenFileAudit(parsed, out, err, cache).run()
    val plan = Engine.plan(parsed.request(cache) { text, error -> (if (error) err else out).print(text) })
    describe(plan, parsed, out)
    val blocker = plan.blocker
    if (blocker != null) err.println(explain(blocker, parsed, plan))
    val runnable = blocker == null || blocker == EngineBlocker.UNRESOLVED_CHANGES
    if (!runnable || parsed.command == "plan") return exitCode(passed = true, blocker)
    return if (parsed.command == "audit") audit(plan, parsed, out, blocker) else run(plan, out, blocker)
}

private fun run(plan: EnginePlan, out: PrintStream, blocker: EngineBlocker?): Int {
    if (plan.plan.isEmpty) return exitCode(passed = true, blocker)
    val passed = runBlocking { Engine.run(plan) }
    out.println(if (passed) "\nAffected: all planned checks passed." else "\nAffected: checks failed.")
    return exitCode(passed, blocker)
}

private fun misplaced(arguments: Arguments): String? {
    val report = arguments.audit.report
    return when {
        !arguments.directory.isDirectory -> "Not a directory: ${arguments.directory}"
        report != null && (report.isDirectory || report.startsWith(arguments.directory)) ->
            "The report must be a file outside ${arguments.directory}: $report"
        else -> null
    }
}

internal fun Arguments.request(cache: Path, output: (String, Boolean) -> Unit): EngineRequest =
    EngineRequest(directory, baseBranch, cache, testDependents, checkConsumers, stopAfterFirstFailure, output)

private fun audit(plan: EnginePlan, arguments: Arguments, out: PrintStream, blocker: EngineBlocker?): Int {
    val audit = runBlocking { Engine.audit(plan) }
    out.println("\n" + summary(plan, audit))
    audit.missed.forEach { out.println("  ${describe(it, arguments)}") }
    arguments.audit.report?.let { writeFullRunReport(it, plan, audit, arguments) }
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

private fun describe(group: TaskGroup, arguments: Arguments): String =
    "${group.systemId} in ${relativeRoot(group, arguments)}: ${group.tasks.joinToString(" ")}"

internal fun relativeRoot(group: TaskGroup, arguments: Arguments): String =
    File(group.root).relativeToOrSelf(arguments.directory).invariantSeparatorsPath.ifEmpty { "." }

internal fun exitCode(passed: Boolean, blocker: EngineBlocker?): Int = when {
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

internal fun explain(blocker: EngineBlocker, arguments: Arguments, plan: EnginePlan): String = when (blocker) {
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
