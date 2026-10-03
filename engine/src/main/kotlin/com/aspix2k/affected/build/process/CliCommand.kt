package com.aspix2k.affected.build.process

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal sealed interface CliStep {
    fun resolve(): CliCommand?
}

internal data class CliCommand(
    val title: String,
    val arguments: List<String>,
    val environment: Map<String, String> = emptyMap(),
    val continueOnFailure: Boolean = false,
    val ownedTemporaryDirectories: List<Path> = emptyList(),
) : CliStep {
    init {
        require(title.isNotBlank())
        require(arguments.isNotEmpty())
        require(ownedTemporaryDirectories.all(::isOwnedTemporaryDirectory))
    }

    override fun resolve(): CliCommand = this
}

internal class DeferredCliCommand private constructor(
    private val command: () -> CliCommand?,
) : CliStep {
    constructor(
        title: String,
        environment: () -> Map<String, String>,
        arguments: () -> List<String>?,
    ) : this({ arguments()?.let { CliCommand(title, it, environment()) } })

    constructor(title: String, arguments: () -> List<String>?) : this(title, { emptyMap() }, arguments)

    override fun resolve(): CliCommand? = command()

    companion object {
        fun command(resolve: () -> CliCommand?): DeferredCliCommand = DeferredCliCommand(resolve)
    }
}

internal fun isOwnedTemporaryDirectory(path: Path): Boolean = runCatching {
    val normalized = path.toAbsolutePath().normalize()
    normalized == path &&
        normalized.fileName.toString().startsWith(OWNED_TEMPORARY_PREFIX) &&
        Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) &&
        !Files.isSymbolicLink(normalized) &&
        normalized.toRealPath().startsWith(temporaryRoot())
}.getOrDefault(false)

private fun temporaryRoot(): Path = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()

private const val OWNED_TEMPORARY_PREFIX = "affected-"
