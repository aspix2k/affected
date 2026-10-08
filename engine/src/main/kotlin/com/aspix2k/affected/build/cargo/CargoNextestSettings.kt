package com.aspix2k.affected.build.cargo

import org.tomlj.TomlTable
import java.io.File

internal fun cargoNextestCarriedSettings(root: File, profile: String): String =
    readCargoNextestConfiguration(root, profile)?.carried.orEmpty()

internal fun carriedNextestSettings(parsed: TomlTable, profile: String): String? {
    val profiles = parsed.get("profile") as? TomlTable ?: return ""
    val layers = listOfNotNull(profiles.get(profile) as? TomlTable, profiles.get("default") as? TomlTable)
    return CARRIED_PROFILE_KEYS.mapNotNull { key ->
        val value = layers.firstNotNullOfOrNull { it.get(key) } ?: return@mapNotNull null
        "$key = ${carriedNextestValue(value) ?: return null}\n"
    }.joinToString("")
}

internal fun carriedNextestValue(value: Any?, nested: Boolean = false): String? = when (value) {
    is Boolean -> value.toString()
    is Long -> value.takeIf { it >= 0 }?.toString()
    is String -> value.takeIf(CARRIED_STRING::matches)?.let { "\"$it\"" }
    is TomlTable -> value.takeUnless { nested }?.keySet()?.map { key ->
        if (!CARRIED_TABLE_KEY.matches(key)) return null
        "$key = ${carriedNextestValue(value.get(key), nested = true) ?: return null}"
    }?.joinToString(", ", "{ ", " }")
    else -> null
}

internal val CARRIED_PROFILE_KEYS = setOf(
    "retries",
    "flaky-result",
    "test-threads",
    "threads-required",
    "slow-timeout",
    "leak-timeout",
    "global-timeout",
)
private val CARRIED_STRING = Regex("[A-Za-z0-9_.+-]{1,64}")
private val CARRIED_TABLE_KEY = Regex("[a-z][a-z0-9-]{0,31}")
