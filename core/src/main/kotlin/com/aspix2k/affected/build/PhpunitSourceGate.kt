package com.aspix2k.affected.build

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal fun supportedPhpunitSources(root: Path, artifacts: Set<String>, tests: List<Path>): Boolean = runCatching {
    val paths = artifacts.map(root::resolve) + tests
    val sources = paths.associateWith { path ->
        maskPhpunitLiterals(Files.readString(path, StandardCharsets.UTF_8))
    }
    val declaredClasses = sources.flatMapTo(LinkedHashSet()) { (path, source) ->
        declaredPhpunitClasses(path, source)
    }
    sources.all { (_, source) -> closedPhpunitSource(source, declaredClasses) }
}.getOrDefault(false)

private fun declaredPhpunitClasses(path: Path, source: String): Set<String> {
    val namespace = phpunitNamespace(source)
    return PHPUNIT_CLASS_DECLARATION.findAll(source).mapTo(LinkedHashSet()) { match ->
        listOf(namespace, match.groupValues[1]).filter(String::isNotBlank).joinToString("\\")
    }.also { require(it.isNotEmpty() || path.fileName.toString().endsWith("Test.php")) }
}

private fun closedPhpunitSource(source: String, declaredClasses: Set<String>): Boolean = runCatching {
    require(supportedPhpunitSyntax(source))
    val namespace = phpunitNamespace(source)
    val imports = phpunitImports(source)
    require(supportedPhpunitImports(source))
    require(supportedPhpunitReferences(source, namespace, imports, declaredClasses))
    require(supportedPhpunitCalls(source))
}.isSuccess

private fun supportedPhpunitSyntax(source: String): Boolean =
    '$' !in source && '`' !in source && "<<<" !in source && "->" !in source && "#[" !in source &&
        !PHPUNIT_DYNAMIC_DISPATCH.containsMatchIn(source) && !PHPUNIT_ELLIPSIS.containsMatchIn(source) &&
        PHPUNIT_NAMESPACE_KEYWORD.findAll(source).count() <= 1 && !PHPUNIT_NAMESPACE_BLOCK.containsMatchIn(source) &&
        !PHPUNIT_UNSAFE_GLOBAL.containsMatchIn(source) &&
        !PHPUNIT_NEW.containsMatchIn(source) && !PHPUNIT_VARIABLE_CALL.containsMatchIn(source) &&
        !PHPUNIT_TRAIT_DECLARATION.containsMatchIn(source) && !PHPUNIT_IMPLEMENTS.containsMatchIn(source)

private fun supportedPhpunitImports(source: String): Boolean {
    val declaration = PHPUNIT_CLASS_DECLARATION.find(source)?.range?.first ?: source.length
    val imports = PHPUNIT_USE.findAll(source).toList()
    return PHPUNIT_USE_KEYWORD.findAll(source).count() == imports.size && imports.all { it.range.first < declaration }
}

private fun supportedPhpunitReferences(
    source: String,
    namespace: String,
    imports: Map<String, String>,
    declaredClasses: Set<String>,
): Boolean = runCatching {
    PHPUNIT_STATIC_REFERENCE.findAll(source).forEach { match ->
        val receiver = match.groupValues[1]
        val member = match.groupValues[2]
        if (receiver == "self") {
            require(member in PHPUNIT_PURE_ASSERTIONS)
        } else {
            val resolved = resolvePhpunitClass(receiver, namespace, imports)
            require(resolved in declaredClasses && member == "value")
        }
    }
    require(PHPUNIT_SCOPE_RESOLUTION.findAll(source).count() == PHPUNIT_STATIC_REFERENCE.findAll(source).count())
    PHPUNIT_EXTENDS.findAll(source).map { it.groupValues[1] }.forEach { receiver ->
        val parent = resolvePhpunitClass(receiver, namespace, imports)
        require(parent == PHPUNIT_TEST_CASE || parent in declaredClasses)
    }
    require(PHPUNIT_EXTENDS_KEYWORD.findAll(source).count() == PHPUNIT_EXTENDS.findAll(source).count())
}.isSuccess

private fun supportedPhpunitCalls(source: String): Boolean = runCatching {
    PHPUNIT_DIRECT_CALL.findAll(source).forEach { match ->
        val name = match.groupValues[1]
        val prefix = source.substring(0, match.range.first).trimEnd()
        if (prefix.endsWith("::") || prefix.endsWith("->") || prefix.endsWith("function")) return@forEach
        require(name.lowercase() in PHPUNIT_LANGUAGE_CALLS)
    }
}.isSuccess

private fun maskPhpunitLiterals(source: String): String {
    require("<<<" !in source)
    val masked = source.toCharArray()
    var index = 0
    while (index < source.length) {
        index = when {
            source.startsWith("//", index) || source[index] == '#' -> {
                require(!source.startsWith("#[", index))
                maskPhpunitLineComment(source, masked, index)
            }
            source.startsWith("/*", index) -> maskPhpunitBlockComment(source, masked, index)
            source[index] == '\'' -> maskPhpunitQuoted(source, masked, index, '\'', interpolation = false)
            source[index] == '"' -> maskPhpunitQuoted(source, masked, index, '"', interpolation = true)
            source[index] == '`' -> error("PHP shell strings are unsupported")
            else -> index + 1
        }
    }
    return String(masked)
}

private fun maskPhpunitLineComment(source: String, masked: CharArray, start: Int): Int {
    var index = start
    while (index < source.length && source[index] != '\n') masked[index++] = ' '
    return index
}

private fun maskPhpunitBlockComment(source: String, masked: CharArray, start: Int): Int {
    val end = source.indexOf("*/", start + 2)
    require(end >= 0)
    for (index in start until end + 2) if (source[index] != '\n') masked[index] = ' '
    return end + 2
}

private fun maskPhpunitQuoted(
    source: String,
    masked: CharArray,
    start: Int,
    quote: Char,
    interpolation: Boolean,
): Int {
    var index = start + 1
    masked[start] = ' '
    while (index < source.length) {
        val character = source[index]
        if (interpolation) require(character != '$')
        masked[index] = if (character == '\n') '\n' else ' '
        if (character == '\\') {
            index++
            require(index < source.length)
            masked[index] = if (source[index] == '\n') '\n' else ' '
        } else if (character == quote) {
            return index + 1
        }
        index++
    }
    error("Unterminated PHP string")
}

private fun phpunitNamespace(source: String): String =
    PHPUNIT_NAMESPACE.find(source)?.groupValues?.get(1)?.trim().orEmpty()

private fun phpunitImports(source: String): Map<String, String> = buildMap {
    PHPUNIT_USE.findAll(source).forEach { match ->
        val imported = match.groupValues[1].trim().removePrefix("\\")
        require(',' !in imported && '{' !in imported)
        val alias = match.groupValues[2].ifBlank { imported.substringAfterLast('\\') }
        require(put(alias, imported) == null)
    }
}

private fun resolvePhpunitClass(receiver: String, namespace: String, imports: Map<String, String>): String {
    if (receiver.startsWith('\\')) return receiver.removePrefix("\\")
    val head = receiver.substringBefore('\\')
    val imported = imports[head]
    if (imported != null) return imported + receiver.removePrefix(head)
    return listOf(namespace, receiver).filter(String::isNotBlank).joinToString("\\")
}

private val PHPUNIT_NAMESPACE = Regex("(?m)^\\s*namespace\\s+([^;{]+)\\s*[;{]")
private val PHPUNIT_NAMESPACE_KEYWORD = Regex("\\bnamespace\\b", RegexOption.IGNORE_CASE)
private val PHPUNIT_USE = Regex("(?m)^\\s*use\\s+(?!function\\b|const\\b)([^;\\s]+)(?:\\s+as\\s+([A-Za-z_]\\w*))?\\s*;")
private val PHPUNIT_CLASS_DECLARATION = Regex("\\b(?:class|interface|trait|enum)\\s+([A-Za-z_]\\w*)")
private val PHPUNIT_STATIC_REFERENCE = Regex(
    "(?<![A-Za-z0-9_\\\\])([\\\\A-Za-z_][\\\\A-Za-z0-9_]*)\\s*::\\s*([A-Za-z_]\\w*)",
)
private val PHPUNIT_SCOPE_RESOLUTION = Regex("::")
private val PHPUNIT_DIRECT_CALL = Regex("\\b([A-Za-z_]\\w*)\\s*\\(")
private val PHPUNIT_NEW = Regex("\\bnew\\s+", RegexOption.IGNORE_CASE)
private val PHPUNIT_VARIABLE_CALL = Regex("\\$[A-Za-z_]\\w*\\s*\\(")
private val PHPUNIT_EXTENDS = Regex(
    "\\bextends\\s+([\\\\A-Za-z_][\\\\A-Za-z0-9_]*)",
    RegexOption.IGNORE_CASE,
)
private val PHPUNIT_EXTENDS_KEYWORD = Regex("\\bextends\\b", RegexOption.IGNORE_CASE)
private val PHPUNIT_IMPLEMENTS = Regex("\\bimplements\\b", RegexOption.IGNORE_CASE)
private val PHPUNIT_TRAIT_DECLARATION = Regex("\\btrait\\s+", RegexOption.IGNORE_CASE)
private val PHPUNIT_USE_KEYWORD = Regex("\\buse\\s+", RegexOption.IGNORE_CASE)
private val PHPUNIT_NAMESPACE_BLOCK = Regex("\\bnamespace\\s+[^;{]*\\{")
private val PHPUNIT_DYNAMIC_DISPATCH = Regex("[)\\]}]\\s*(?:\\(|::)")
private val PHPUNIT_ELLIPSIS = Regex("\\.\\.\\.")
private val PHPUNIT_UNSAFE_GLOBAL =
    Regex("\\bglobal\\s+|\\$(?:argv|argc|GLOBALS|_(?:ENV|SERVER|GET|POST|COOKIE|FILES|REQUEST|SESSION))\\b")
private val PHPUNIT_LANGUAGE_CALLS = setOf(
    "array", "catch", "declare", "empty", "for", "foreach", "if", "isset", "list", "match", "switch", "unset", "while",
)
private val PHPUNIT_PURE_ASSERTIONS = setOf(
    "assertEquals",
    "assertFalse",
    "assertGreaterThan",
    "assertGreaterThanOrEqual",
    "assertIdentical",
    "assertLessThan",
    "assertLessThanOrEqual",
    "assertNotEquals",
    "assertNotSame",
    "assertNull",
    "assertSame",
    "assertTrue",
)
private const val PHPUNIT_TEST_CASE = "PHPUnit\\Framework\\TestCase"
