package com.aspix2k.affected.build

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale

internal fun cargoConfigurationExists(root: File, environment: Map<String, String>): Boolean {
    val normalized = normalizedCargoEnvironment(environment) ?: return true
    if (normalized.any(::unsafeCargoEnvironmentEntry)) return true
    val directories = generateSequence(root) { current -> current.parentFile }.toList()
    val configuredCargoHome = normalized["CARGO_HOME"]?.takeIf(String::isNotBlank)?.let(::File)
    if (configuredCargoHome != null && !configuredCargoHome.isAbsolute) return true
    val cargoHome = configuredCargoHome ?: defaultCargoHome(normalized) ?: return true
    return directories.any { directory ->
        listOf(File(directory, ".cargo/config.toml"), File(directory, ".cargo/config"))
            .any { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
    } || listOf(File(cargoHome, "config.toml"), File(cargoHome, "config"))
        .any { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
}

private fun unsafeCargoEnvironmentEntry(entry: Map.Entry<String, String>): Boolean =
    entry.value.isNotBlank() && (
        entry.key in CARGO_RUNNER_ENVIRONMENT ||
            CARGO_TARGET_RUNNER.matches(entry.key) ||
            entry.key.startsWith("CARGO_ALIAS_")
        )

private fun defaultCargoHome(environment: Map<String, String>): File? {
    val systemHome = System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let(::File) ?: return null
    val environmentHomes = buildList {
        environment["HOME"]?.takeIf(String::isNotBlank)?.let(::File)?.let(::add)
        environment["USERPROFILE"]?.takeIf(String::isNotBlank)?.let(::File)?.let(::add)
        val drive = environment["HOMEDRIVE"]?.takeIf(String::isNotBlank)
        val path = environment["HOMEPATH"]?.takeIf(String::isNotBlank)
        if (drive != null && path != null) add(File("$drive$path"))
    }
    if (environmentHomes.any { !it.isAbsolute || it.absoluteFile.normalize() != systemHome.absoluteFile.normalize() }) {
        return null
    }
    return File(systemHome, ".cargo")
}

internal fun unsupportedNextestEnvironment(environment: Map<String, String>): Boolean =
    normalizedCargoEnvironment(environment)?.let { normalized ->
        normalized["CARGO"].isNullOrBlank().not() || normalized.any { (key, value) ->
            value.isNotBlank() && key.startsWith("NEXTEST_") && key != "NEXTEST_PROFILE"
        } || normalized.any { (key, value) -> value.isNotBlank() && key.startsWith("__NEXTEST_") }
    } ?: true

internal fun cargoNextestExecutableStamp(environment: Map<String, String>): String? = runCatching {
    val directories = nextestDirectories(environment) ?: return null
    nextestExecutable(directories)?.let(::cheapExecutableStamp) ?: "missing"
}.getOrNull()

internal fun cargoNextestExecutable(environment: Map<String, String>): java.nio.file.Path? = runCatching {
    val directories = nextestDirectories(environment) ?: return null
    nextestExecutable(directories)?.toRealPath()
}.getOrNull()

internal fun cargoExecutable(environment: Map<String, String>): java.nio.file.Path? = runCatching {
    val directories = nextestDirectories(environment) ?: return null
    val name = if (System.getProperty("os.name").startsWith("Windows")) "cargo.exe" else "cargo"
    directories.asSequence()
        .map { directory -> File(directory, name).toPath().toAbsolutePath().normalize() }
        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
}.getOrNull()

private fun nextestDirectories(environment: Map<String, String>): List<File>? {
    val path = normalizedCargoEnvironment(environment)?.get("PATH") ?: return null
    val directories = path.split(File.pathSeparatorChar)
    if (directories.size > MAX_PATH_DIRECTORIES || directories.any { it.isBlank() || !File(it).isAbsolute }) {
        return null
    }
    return directories.map(::File)
}

private fun nextestExecutable(directories: List<File>): java.nio.file.Path? {
    val names = if (System.getProperty("os.name").startsWith("Windows")) {
        listOf("cargo-nextest.exe")
    } else {
        listOf("cargo-nextest")
    }
    return directories.asSequence()
        .flatMap { directory -> names.asSequence().map { File(directory, it).toPath() } }
        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
}

private fun cheapExecutableStamp(executable: java.nio.file.Path): String {
    val real = executable.toRealPath()
    val attributes = Files.readAttributes(real, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    return listOf(real, attributes.fileKey(), attributes.size(), attributes.lastModifiedTime()).joinToString(":")
}

private fun executableIdentity(executable: java.nio.file.Path): String? {
    val real = executable.toRealPath()
    val before = Files.readAttributes(real, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (before.size() !in 1..MAX_NEXTEST_EXECUTABLE_BYTES) return null
    val digest = Files.newInputStream(real).use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(EXECUTABLE_DIGEST_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_NEXTEST_EXECUTABLE_BYTES) return null
            hash.update(buffer, 0, read)
        }
        hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    val after = Files.readAttributes(real, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (before.fileKey() != after.fileKey() || before.size() != after.size() ||
        before.lastModifiedTime() != after.lastModifiedTime()
    ) {
        return null
    }
    return digest
}

internal fun cargoNextestExecutableIdentity(environment: Map<String, String>): String? =
    cargoNextestExecutable(environment)?.let(::cargoNextestExecutableIdentity)

internal fun cargoNextestExecutableIdentity(executable: java.nio.file.Path): String? =
    runCatching { executableIdentity(executable) }.getOrNull()

internal fun cargoNextestProfile(environment: Map<String, String>): String? =
    normalizedCargoEnvironment(environment)?.get("NEXTEST_PROFILE")

private fun normalizedCargoEnvironment(environment: Map<String, String>): Map<String, String>? {
    if (!System.getProperty("os.name").startsWith("Windows")) return environment
    val values = environment.entries.groupBy { it.key.uppercase(Locale.ROOT) }
    if (values.any { (_, entries) -> entries.size != 1 }) return null
    return values.mapValues { (_, entries) -> entries.single().value }
}

private const val MAX_PATH_DIRECTORIES = 256
private const val MAX_NEXTEST_EXECUTABLE_BYTES = 64L * 1024L * 1024L
private const val EXECUTABLE_DIGEST_BUFFER_BYTES = 64 * 1024
private val CARGO_TARGET_RUNNER = Regex("CARGO_TARGET_[A-Z0-9_]+_RUNNER")
private val CARGO_RUNNER_ENVIRONMENT = setOf(
    "RUSTC", "RUSTDOC", "RUSTC_WRAPPER", "RUSTC_WORKSPACE_WRAPPER",
    "CARGO_BUILD_RUSTC", "CARGO_BUILD_RUSTDOC", "CARGO_BUILD_RUSTC_WRAPPER",
    "CARGO_BUILD_RUSTC_WORKSPACE_WRAPPER",
)
