package com.aspix2k.affected.build.cargo

import com.aspix2k.affected.build.ManifestSearch
import java.io.File

internal fun isCargoWorkspaceMember(root: File, nested: File): Boolean {
    val nestedText = cargoText(nested) ?: return false
    if (CARGO_WORKSPACE.containsMatchIn(nestedText)) return false
    if (CARGO_FUZZ.containsMatchIn(nestedText)) return true
    val text = cargoText(root) ?: return false
    val section = CARGO_WORKSPACE.find(text)?.let { text.substring(it.range.last + 1).substringBefore("\n[") }
        ?: return false
    val relative = root.toPath().toAbsolutePath().normalize()
        .relativize(nested.toPath().toAbsolutePath().normalize())
        .joinToString("/")
    val excluded = cargoPatterns(section, "exclude").any { it.matches(relative) }
    val listed = cargoPatterns(section, "members").any { it.matches(relative) }
    val path = Regex("""path\s*=\s*["'](?:\./)?${Regex.escape(relative)}/?["']""").containsMatchIn(text)
    return !excluded && (listed || path)
}

private fun cargoText(directory: File): String? = ManifestSearch.readText(File(directory, "Cargo.toml"))

private fun cargoPatterns(section: String, key: String): List<Regex> =
    Regex("""(?s)(?:^|\n)\s*$key\s*=\s*\[(.*?)]""").find(section)?.groupValues?.get(1)
        ?.let { CARGO_STRING.findAll(it).map { match -> cargoGlob(match.groupValues[1]) }.toList() }
        .orEmpty()

private fun cargoGlob(pattern: String): Regex = Regex(
    pattern.removePrefix("./").trimEnd('/').split("**").joinToString(".*") { part ->
        part.split('*').joinToString("[^/]*") { literal ->
            literal.split('?').joinToString("[^/]") { Regex.escape(it) }
        }
    },
)

private val CARGO_WORKSPACE = Regex("""(?m)^\s*\[workspace]""")
private val CARGO_FUZZ = Regex("""(?m)^\s*cargo-fuzz\s*=\s*true""")
private val CARGO_STRING = Regex("""["']([^"']+)["']""")
