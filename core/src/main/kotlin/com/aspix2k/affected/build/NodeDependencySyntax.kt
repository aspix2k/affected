package com.aspix2k.affected.build

internal fun hasDynamicNodeDependency(source: String): Boolean {
    var index = 0
    while (index < source.length) {
        val start = source.indexOfAny(charArrayOf('i', 'r', 'c', 'e', 'F'), index)
        if (start < 0) return false
        when {
            source.isIdentifierAt(start, "import") && source.hasDynamicImportAt(start) -> return true
            source.isIdentifierAt(start, "require") && source.hasDynamicRequireAt(start) -> return true
            source.isIdentifierAt(start, "import") && source.hasImportMetaGlobAt(start) -> return true
            source.isIdentifierAt(start, "createRequire") -> return true
            source.isIdentifierAt(start, "eval") -> return true
            source.isIdentifierAt(start, "Function") -> return true
        }
        index = start + 1
    }
    return false
}

private fun String.hasImportMetaGlobAt(start: Int): Boolean {
    val meta = skipTrivia(start + "import".length) ?: return true
    if (!startsWith(".meta", meta)) return false
    val glob = skipTrivia(meta + ".meta".length) ?: return true
    return startsWith(".glob", glob)
}

private fun String.hasDynamicImportAt(start: Int): Boolean {
    val next = skipTrivia(start + "import".length) ?: return true
    return getOrNull(next) == '('
}

private fun String.hasDynamicRequireAt(start: Int): Boolean {
    if (previousCodePoint(start) == '.') return true
    val next = skipTrivia(start + "require".length) ?: return true
    if (getOrNull(next) == '(') return !hasSingleLiteralArgument(next + 1)
    if (!startsWith(".resolve", next)) return true
    val open = skipTrivia(next + ".resolve".length) ?: return true
    return getOrNull(open) != '(' || !hasSingleLiteralArgument(open + 1)
}

private fun String.previousCodePoint(from: Int): Char? {
    var index = from - 1
    while (index >= 0 && this[index].isWhitespace()) index--
    return getOrNull(index)
}

private fun String.isIdentifierAt(index: Int, identifier: String): Boolean {
    if (!startsWith(identifier, index)) return false
    val before = getOrNull(index - 1)
    val after = getOrNull(index + identifier.length)
    return before?.isNodeIdentifier() != true && after?.isNodeIdentifier() != true
}

private fun Char.isNodeIdentifier(): Boolean = isLetterOrDigit() || this == '_' || this == '$'

private fun String.skipTrivia(from: Int): Int? {
    var index = from
    while (index < length) {
        when {
            this[index].isWhitespace() -> index++
            startsWith("//", index) -> {
                index = indexOf('\n', index + 2).takeIf { it >= 0 } ?: return length
            }
            startsWith("/*", index) -> {
                val end = indexOf("*/", index + 2)
                if (end < 0) return null
                index = end + 2
            }
            else -> return index
        }
    }
    return index
}

private fun String.hasSingleLiteralArgument(from: Int): Boolean {
    var index = skipTrivia(from) ?: return false
    val quote = getOrNull(index)?.takeIf { it == '\'' || it == '"' } ?: return false
    index++
    while (index < length) {
        when (this[index]) {
            '\\' -> index += 2
            quote -> {
                val close = skipTrivia(index + 1) ?: return false
                return getOrNull(close) == ')'
            }
            '\n', '\r' -> return false
            else -> index++
        }
    }
    return false
}
