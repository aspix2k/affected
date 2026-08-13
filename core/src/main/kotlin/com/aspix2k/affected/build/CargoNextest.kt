package com.aspix2k.affected.build

import com.intellij.openapi.application.PathManager
import org.tomlj.Toml
import org.tomlj.TomlTable
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal enum class CargoNextestMode {
    CARGO_TEST,
    PACKAGES,
    WORKSPACE,
}

internal data class CargoNextestPlan(
    val mode: CargoNextestMode,
    val profile: String?,
    val requiredVersion: String? = null,
    val failFast: Boolean? = null,
    val executableIdentity: String = TEST_EXECUTABLE_IDENTITY,
)

internal fun detectCargoNextest(
    root: File,
    versionOutput: String?,
    configurationOutput: String?,
    requestedProfile: String? = null,
    cargoConfigurationPresent: Boolean = false,
): CargoNextestPlan {
    val config = File(root, ".config/nextest.toml")
    val installed = supportedNextestVersion(versionOutput) ?: return CARGO_TEST_PLAN
    if (!Files.exists(config.toPath(), LinkOption.NOFOLLOW_LINKS)) return CARGO_TEST_PLAN
    val parsed = readCargoNextestConfiguration(root, requestedProfile) ?: return CARGO_TEST_PLAN
    val executionRequired = maxOf(parsed.required, MIN_SUPPORTED_NEXTEST)
    if (!supportsConfiguration(installed, executionRequired, configurationOutput)) {
        return CARGO_TEST_PLAN
    }
    if (cargoConfigurationPresent) return CARGO_TEST_PLAN
    return parsed.plan(CargoNextestMode.PACKAGES, executionRequired)
}

private fun supportedNextestVersion(output: String?): NextestVersion? {
    val lines = output?.trim()?.lines() ?: return null
    val version = lines.firstOrNull()?.let(NEXTEST_VERSION::matchEntire)?.groupValues?.get(1)
        ?.let(NextestVersion::parse) ?: return null
    if (!version.supportedInstalled) return null
    if (lines.size == 1) return version
    return if (matchesDetailedVersion(lines, version)) version else null
}

private fun matchesDetailedVersion(lines: List<String>, version: NextestVersion): Boolean =
    lines.size == 5 &&
        lines[1] == "release: $version" &&
        NEXTEST_COMMIT.matches(lines[2]) &&
        NEXTEST_DATE.matches(lines[3]) &&
        NEXTEST_HOST.matches(lines[4])

internal fun cargoNextestValidationSnapshot(root: File, requestedProfile: String?): File? =
    readCargoNextestConfiguration(root, requestedProfile)
        ?.let { config -> config.plan(CargoNextestMode.PACKAGES, maxOf(config.required, MIN_SUPPORTED_NEXTEST)) }
        ?.let(::cargoNextestTask)
        ?.let(::cargoNextestSnapshot)

private fun readCargoNextestConfiguration(root: File, requestedProfile: String?): NextestConfiguration? = runCatching {
    val config = File(root, ".config/nextest.toml")
    val parsed = Toml.parse(readNextestConfig(root, config) ?: return null)
    if (parsed.hasErrors()) return null
    val required = requiredVersion(parsed)?.takeIf { it.supportedRequired } ?: return null
    val profile = resolvedProfile(parsed, requestedProfile) ?: return null
    val failFast = resolvedFailFast(parsed, profile) ?: return null
    NextestConfiguration(profile, required, failFast)
}.getOrNull()

private fun readNextestConfig(root: File, config: File): String? {
    val rootAlias = root.toPath().toAbsolutePath().normalize()
    val rootPath = rootAlias.toRealPath()
    val requested = config.toPath().toAbsolutePath().normalize()
    if (!requested.startsWith(rootAlias)) return null
    var current = rootAlias
    rootAlias.relativize(requested).forEach { component ->
        current = current.resolve(component)
        if (Files.isSymbolicLink(current)) return null
    }
    if (Files.isSymbolicLink(requested) || !Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS)) return null
    val real = requested.toRealPath()
    if (!real.startsWith(rootPath)) return null
    val size = Files.size(real)
    if (size !in 1..MAX_NEXTEST_CONFIG_BYTES) return null
    return readUtf8RegularFile(real, MAX_NEXTEST_CONFIG_BYTES)
}

private fun readUtf8RegularFile(file: java.nio.file.Path, maxBytes: Long): String? {
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
    val size = Files.size(file)
    if (size !in 1..maxBytes) return null
    val bytes = Files.newInputStream(file).use { it.readNBytes(maxBytes.toInt() + 1) }
    if (bytes.size > maxBytes) return null
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}

private fun resolvedProfile(parsed: TomlTable, requestedProfile: String?): String? {
    val profile = requestedProfile?.takeIf(::validProfileName) ?: requestedProfile?.let { return null } ?: "default"
    val profiles: TomlTable? = if (parsed.contains("profile")) {
        runCatching { parsed.getTable("profile") }.getOrNull() ?: return null
    } else {
        null
    }
    if (profile != "default" && profiles?.get(profile) !is TomlTable) return null
    return profile.takeIf { supportedNextestProfiles(parsed, profiles) }
}

private fun resolvedFailFast(parsed: TomlTable, profile: String): Boolean? {
    val profiles = parsed.get("profile") as? TomlTable ?: return true
    val selected = profiles.get(profile) as? TomlTable ?: return if (profile == "default") true else null
    val inherited = (profiles.get("default") as? TomlTable)?.get("fail-fast") ?: true
    return when (val value = selected.get("fail-fast") ?: inherited) {
        is Boolean -> value
        else -> null
    }
}

private fun supportsConfiguration(
    installed: NextestVersion,
    required: NextestVersion,
    output: String?,
): Boolean = installed >= required && supportedNextestConfigurationOutput(output, installed, required)

private fun supportedNextestConfigurationOutput(
    output: String?,
    installed: NextestVersion,
    required: NextestVersion,
): Boolean = output?.trim() == """
    current nextest version: $installed
    version requirements:
        - required: $required
    evaluation result: ok
""".trimIndent()

private fun requiredVersion(config: TomlTable): NextestVersion? {
    val value = config.get("nextest-version") ?: return null
    return when (value) {
        is String -> NextestVersion.parse(value)
        is TomlTable -> if (value.keySet() == setOf("required")) {
            (value.get("required") as? String)?.let(NextestVersion::parse)
        } else {
            null
        }
        else -> null
    }
}

private fun supportedNextestProfiles(config: TomlTable, profiles: TomlTable?): Boolean {
    if (config.keySet() - SUPPORTED_ROOT_KEYS != emptySet<String>()) return false
    profiles ?: return true
    return profiles.keySet().all { name ->
        validProfileName(name) && (profiles.get(name) as? TomlTable)?.let { profile ->
            profile.keySet().all { key ->
                when (key) {
                    "fail-fast" -> profile.get(key) is Boolean
                    else -> false
                }
            }
        } == true
    }
}

internal fun cargoNextestTask(profile: String): String {
    require(validProfileName(profile))
    return cargoNextestTask(CargoNextestPlan(CargoNextestMode.PACKAGES, profile, "0.9.143", true))
}

internal fun cargoNextestTask(plan: CargoNextestPlan, hasDoctests: Boolean = true): String {
    val profile = requireNotNull(plan.profile).also { require(validProfileName(it)) }
    val required = requireNotNull(plan.requiredVersion).also {
        require(NextestVersion.parse(it)?.supportedRequired == true)
    }
    val failFast = requireNotNull(plan.failFast)
    val prefix = if (plan.mode == CargoNextestMode.WORKSPACE) CARGO_NEXTEST_WORKSPACE_TASK else "nextest"
    require(EXECUTABLE_IDENTITY.matches(plan.executableIdentity))
    return listOf(prefix, profile, required, failFast, plan.executableIdentity, hasDoctests).joinToString("@")
}

internal fun cargoNextestWorkspaceTask(task: String): Boolean = task.startsWith("$CARGO_NEXTEST_WORKSPACE_TASK@")

internal fun cargoNextestExecutableIdentityFromTask(task: String): String? = task.substringAfterLast(':').split('@')
    .takeIf { it.size == 6 && it.first() in setOf("nextest", CARGO_NEXTEST_WORKSPACE_TASK) }
    ?.get(4)
    ?.takeIf(EXECUTABLE_IDENTITY::matches)

internal fun cargoNextestSnapshot(task: String): File? = runCatching {
    val parts = task.split('@')
    if (parts.size != 6 || parts.first() !in setOf("nextest", CARGO_NEXTEST_WORKSPACE_TASK)) return null
    val profile = parts[1].takeIf(::validProfileName) ?: return null
    val required = NextestVersion.parse(parts[2])?.takeIf { it.supportedRequired } ?: return null
    val failFast = parts[3].toBooleanStrictOrNull() ?: return null
    if (!EXECUTABLE_IDENTITY.matches(parts[4])) return null
    parts[5].toBooleanStrictOrNull() ?: return null
    val content = """
        nextest-version = { required = "$required" }

        [profile.$profile]
        fail-fast = $failFast
    """.trimIndent() + "\n"
    val id = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val realDirectory = secureNextestDirectory() ?: return null
    val target = realDirectory.resolve("$id.toml")
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        if (readUtf8RegularFile(target, MAX_NEXTEST_SNAPSHOT_BYTES) != content) return null
    } else {
        writeNextestSnapshot(realDirectory, target, content) ?: return null
    }
    target.toFile()
}.getOrNull()

private fun secureNextestDirectory(): java.nio.file.Path? = runCatching {
    var current = PathManager.getSystemDir().toAbsolutePath().normalize()
    if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) return null
    listOf("affected", "cargo", "nextest-config").forEach { component ->
        current = current.resolve(component)
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) return null
        } else {
            Files.createDirectory(current)
        }
    }
    current.toRealPath()
}.getOrNull()

private fun writeNextestSnapshot(
    directory: java.nio.file.Path,
    target: java.nio.file.Path,
    content: String,
): Boolean? {
    val temporary = Files.createTempFile(directory, "nextest-", ".tmp")
    return try {
        Files.writeString(temporary, content, StandardCharsets.UTF_8)
        runCatching { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE) }
            .fold(
                onSuccess = { true },
                onFailure = { readUtf8RegularFile(target, MAX_NEXTEST_SNAPSHOT_BYTES) == content },
            )
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private data class NextestVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<NextestVersion> {
    val supportedInstalled: Boolean get() = major == 0 && minor == 9 && patch >= 143
    val supportedRequired: Boolean get() = major == 0 && minor == 9 && patch >= 85

    override fun compareTo(other: NextestVersion): Int =
        compareValuesBy(this, other, NextestVersion::major, NextestVersion::minor, NextestVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(
            value: String
        ): NextestVersion? = VERSION.matchEntire(value)?.destructured?.let { (major, minor, patch) ->
            NextestVersion(
                major.toIntOrNull() ?: return null,
                minor.toIntOrNull() ?: return null,
                patch.toIntOrNull() ?: return null,
            )
        }
    }
}

private data class NextestConfiguration(
    val profile: String,
    val required: NextestVersion,
    val failFast: Boolean,
) {
    fun plan(mode: CargoNextestMode, executionRequired: NextestVersion): CargoNextestPlan =
        CargoNextestPlan(mode, profile, executionRequired.toString(), failFast)
}

private const val MAX_NEXTEST_CONFIG_BYTES = 64L * 1024L
private const val MAX_NEXTEST_SNAPSHOT_BYTES = 1024L
private const val CARGO_NEXTEST_WORKSPACE_TASK = "nextest-workspace"
private const val TEST_EXECUTABLE_IDENTITY = "test"
private val CARGO_TEST_PLAN = CargoNextestPlan(CargoNextestMode.CARGO_TEST, null)
private val MIN_SUPPORTED_NEXTEST = NextestVersion(0, 9, 143)
private val SUPPORTED_ROOT_KEYS = setOf("nextest-version", "profile")
private val VERSION = Regex("([0-9]+)\\.([0-9]+)\\.([0-9]+)")
private val NEXTEST_VERSION = Regex("cargo-nextest ([0-9]+\\.[0-9]+\\.[0-9]+)(?: \\(.*\\))?")
private val NEXTEST_COMMIT = Regex("commit-hash: [0-9a-f]{40}")
private val NEXTEST_DATE = Regex("commit-date: [0-9]{4}-[0-9]{2}-[0-9]{2}")
private val NEXTEST_HOST = Regex("host: [A-Za-z0-9_.-]{1,128}")
private val PROFILE_NAME = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
private val EXECUTABLE_IDENTITY = Regex("(?:test|[0-9a-f]{64})")

private fun validProfileName(value: String): Boolean =
    PROFILE_NAME.matches(value) && (value == "default" || !value.startsWith("default-"))
