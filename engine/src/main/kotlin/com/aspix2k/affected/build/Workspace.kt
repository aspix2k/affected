package com.aspix2k.affected.build

import com.aspix2k.affected.build.process.CliStep
import com.aspix2k.affected.build.process.CommandCapture
import com.aspix2k.affected.build.process.DEFAULT_CAPTURE_LIMIT
import com.aspix2k.affected.build.process.ProcessHost
import java.io.File
import java.nio.file.Path

internal interface Workspace {
    val root: File?

    val cacheDirectory: Path

    val stopAfterFirstFailure: Boolean

    val helperAssets: Path?

    val processHost: ProcessHost

    val commandRuns: CommandRuns

    fun interpreterHint(directory: File): String?

    fun changes(): BuildChanges
}

internal class CommandRun(
    val workingDirectory: String,
    val steps: List<CliStep>,
    val title: String,
    val unresolvedMessage: String?,
    val continueAfterFailure: Boolean,
)

internal interface CommandRuns {
    fun start(run: CommandRun)

    suspend fun await(run: CommandRun): Boolean
}

internal fun Workspace.runBatch(
    workingDirectory: String,
    steps: List<CliStep>,
    title: String,
    unresolvedMessage: String? = null,
    continueAfterFailure: Boolean = continuesAfterFailure(stopAfterFirstFailure),
) = commandRuns.start(CommandRun(workingDirectory, steps, title, unresolvedMessage, continueAfterFailure))

internal suspend fun Workspace.runBatchAndWait(
    workingDirectory: String,
    steps: List<CliStep>,
    title: String,
    unresolvedMessage: String? = null,
    continueAfterFailure: Boolean = continuesAfterFailure(stopAfterFirstFailure),
): Boolean = commandRuns.await(CommandRun(workingDirectory, steps, title, unresolvedMessage, continueAfterFailure))

internal fun Workspace.capture(
    workingDirectory: String,
    command: List<String>,
    timeoutSeconds: Long = 60,
    maxBytes: Int = DEFAULT_CAPTURE_LIMIT,
    environment: Map<String, String> = emptyMap(),
): String? = CommandCapture(processHost).capture(workingDirectory, command, timeoutSeconds, maxBytes, environment)
