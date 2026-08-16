package com.aspix2k.affected.build

import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun pythonDeferredCommands(
    root: String,
    tasks: List<String>,
    modules: List<BuildModule>,
    planned: BuildChanges,
    adapter: Path,
    runner: PythonTestRunner = pythonTestRunner(File(root)),
    currentChanges: () -> BuildChanges,
): List<CliStep> {
    val adapterStamp = adapterFingerprint(adapter)
    val plannedCommands = resolvedPythonCommands(root, tasks, modules, planned, adapter, runner = runner)
    val fullCommands = resolvedPythonCommands(root, tasks, modules, null, null, runner = runner)
    val fullAdapterCommands = resolvedPythonCommands(
        root,
        tasks,
        modules,
        changes = null,
        adapter = adapter,
        unittestAdapterFallback = true,
        runner = runner,
    )
    if (plannedCommands == fullCommands) {
        return fullAdapterCommands.map { command ->
            deferredUnittestCommand(command, root, runner, adapter, adapterStamp) { command.arguments }
        }
    }
    return plannedCommands.map { command ->
        deferredUnittestCommand(command, root, runner, adapter, adapterStamp) {
            val current = currentPythonChangesOrNull(currentChanges)
            val effective = current?.takeIf { samePythonChanges(planned, it) }
            resolvedPythonCommands(
                root,
                tasks,
                modules,
                effective,
                adapter,
                unittestAdapterFallback = true,
                runner = runner,
            ).filterNot { it.title == "mypy" }
                .singleOrNull()
                ?.takeIf { it.title == "unittest" || it.title == "unittest package set unresolved" }
                ?.arguments
                ?: adapterDriftFailure()
        }
    }
}

private fun deferredUnittestCommand(
    command: CliCommand,
    root: String,
    runner: PythonTestRunner,
    adapter: Path,
    adapterStamp: String?,
    arguments: () -> List<String>,
): CliStep {
    if (command.title != "unittest") return command
    return DeferredCliCommand(command.title) {
        revalidatedUnittestArguments(root, runner, adapter, adapterStamp, arguments)
    }
}

private fun revalidatedUnittestArguments(
    root: String,
    runner: PythonTestRunner,
    adapter: Path,
    adapterStamp: String?,
    arguments: () -> List<String>,
): List<String> {
    val runnerDeadline = System.nanoTime() + PerformanceBudgets.SCAN_TIME_NS
    if (adapterStamp == null || adapterFingerprint(adapter) != adapterStamp) return adapterDriftFailure()
    if (pythonTestRunner(File(root), runnerDeadline) != runner) {
        return listOf("python", "-c", PYTHON_RUNNER_DRIFT_FAILURE)
    }
    val resolved = arguments()
    if (adapterFingerprint(adapter) != adapterStamp) return adapterDriftFailure()
    if (pythonTestRunner(File(root), runnerDeadline) != runner) {
        return listOf("python", "-c", PYTHON_RUNNER_DRIFT_FAILURE)
    }
    return resolved
}

private fun adapterFingerprint(adapter: Path): String? = adapter.parent?.let { root ->
    if (
        Files.isRegularFile(adapter, LinkOption.NOFOLLOW_LINKS) &&
        Files.isReadable(adapter) &&
        !Files.isSymbolicLink(adapter)
    ) {
        ManifestSearch.fingerprint(root.toFile(), listOf(adapter.toFile()))
    } else {
        null
    }
}

private fun adapterDriftFailure(): List<String> = listOf("python", "-c", UNITTEST_ADAPTER_DRIFT_FAILURE)

private fun samePythonChanges(planned: BuildChanges, current: BuildChanges): Boolean =
    planned.comparedToBase == current.comparedToBase &&
        planned.files.toSet() == current.files.toSet() &&
        planned.exactSelectionEligible == current.exactSelectionEligible

private fun currentPythonChangesOrNull(currentChanges: () -> BuildChanges): BuildChanges? = try {
    currentChanges()
} catch (error: CancellationException) {
    throw error
} catch (error: ProcessCanceledException) {
    throw error
} catch (error: InterruptedException) {
    Thread.currentThread().interrupt()
    throw error
} catch (_: Exception) {
    null
}

private const val PYTHON_RUNNER_DRIFT_FAILURE =
    "import sys; sys.stderr.write(\"Affected detected a Python test-runner change after planning; " +
        "refresh the project model and run again.\\n\"); raise SystemExit(2)"
private const val UNITTEST_ADAPTER_DRIFT_FAILURE =
    "import sys; sys.stderr.write(\"Affected could not revalidate the packaged unittest adapter; " +
        "reinstall or rebuild the plugin and run again.\\n\"); raise SystemExit(2)"
