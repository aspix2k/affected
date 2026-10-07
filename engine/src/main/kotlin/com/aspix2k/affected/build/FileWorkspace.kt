package com.aspix2k.affected.build

import com.aspix2k.affected.build.process.CliCommand
import com.aspix2k.affected.build.process.CommandSequence
import com.aspix2k.affected.build.process.CommandSequenceListener
import com.aspix2k.affected.build.process.OutputKind
import com.aspix2k.affected.build.process.ProcessHost
import com.sun.jna.Platform
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class FileWorkspace(
    directory: File,
    override val cacheDirectory: Path,
    override val stopAfterFirstFailure: Boolean,
    private val collectedChanges: () -> BuildChanges,
    private val output: (String, Boolean) -> Unit,
) : Workspace {

    override val root: File = directory

    override val helperAssets: Path? = runCatching {
        Path.of(FileWorkspace::class.java.protectionDomain.codeSource.location.toURI())
    }.getOrNull()

    override val processHost: ProcessHost by lazy { ProcessHost.standalone(listOf(extractJnaNatives())) }

    override val commandRuns: CommandRuns = object : CommandRuns {

        override fun start(run: CommandRun) {
            sequence(run, CompletableDeferred()).start()
        }

        override suspend fun await(run: CommandRun): Boolean {
            val exit = CompletableDeferred<Int>()
            val sequence = sequence(run, exit)
            sequence.start()
            return try {
                exit.await() == 0
            } finally {
                sequence.requestStop()
            }
        }
    }

    override fun interpreterHint(directory: File): String? = null

    override fun changes(): BuildChanges = collectedChanges()

    private fun sequence(run: CommandRun, exit: CompletableDeferred<Int>): CommandSequence {
        val listener = object : CommandSequenceListener {

            override fun onText(text: String, kind: OutputKind) = output(text, kind == OutputKind.STDERR)

            override fun onCommandStarted(command: CliCommand) =
                output("> ${command.arguments.joinToString(" ")}\n", false)

            override fun onTerminated(exitCode: Int) {
                exit.complete(exitCode)
            }
        }
        return if (run.unresolvedMessage == null) {
            CommandSequence(
                File(run.workingDirectory),
                run.steps,
                listener,
                processHost,
                continueAfterFailure = run.continueAfterFailure,
            )
        } else {
            CommandSequence(
                File(run.workingDirectory),
                run.steps,
                listener,
                processHost,
                run.unresolvedMessage,
                run.continueAfterFailure,
            )
        }
    }
}

private fun extractJnaNatives(): Path {
    val names = listOf(System.mapLibraryName("jnidispatch"), "libjnidispatch.jnilib")
    val resource = names.firstNotNullOfOrNull { name ->
        Platform::class.java.getResourceAsStream("/com/sun/jna/${Platform.RESOURCE_PREFIX}/$name")?.let { name to it }
    } ?: throw IOException("The JNA native library is missing from the classpath")
    val directory = Files.createTempDirectory("affected-jna-")
    directory.toFile().deleteOnExit()
    resource.second.use { Files.copy(it, directory.resolve(resource.first)) }
    directory.resolve(resource.first).toFile().deleteOnExit()
    return directory
}
