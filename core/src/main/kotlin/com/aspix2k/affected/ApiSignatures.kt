package com.aspix2k.affected

internal object ApiSignatures {

    fun changed(diff: List<String>): Boolean = signatures(diff, "-") != signatures(diff, "+")

    private fun signatures(diff: List<String>, marker: String): Set<String> = diff
        .filter { it.startsWith(marker) }
        .map { it.drop(1) }
        .filter(::isPublicDeclaration)
        .mapTo(HashSet(), ::signatureOf)

    private fun signatureOf(line: String): String {
        val header = line.substringBefore('{')
        var depth = 0
        var typed = false
        var body = header.length
        for ((index, char) in header.withIndex()) {
            when (char) {
                '(', '[', '<' -> depth++
                ')', ']' -> depth--
                '>' -> if (header.getOrNull(index - 1) != '-') depth--
                ':' -> if (depth == 0) typed = true
                '=' -> if (depth == 0) body = minOf(body, index)
            }
        }
        val inferredFromBody = !typed || CONST.containsMatchIn(header)
        return (if (inferredFromBody) header else header.substring(0, body)).trim().replace(WHITESPACE, " ")
    }

    fun isPublicDeclaration(line: String): Boolean {
        val indent = line.takeWhile { it == ' ' }.length

        if (PARAMETER.matches(line) || CONSTRUCTOR_PROPERTY.matches(line)) return indent <= PARAMETER_INDENT
        if (PRIVATE.containsMatchIn(line)) return false
        if (ENUM_CONSTANT.matches(line)) return indent <= MEMBER_INDENT

        val declaration = DECLARATION.containsMatchIn(line) || TYPED_MEMBER.containsMatchIn(line)
        if (!declaration) return false

        if (indent <= MEMBER_INDENT) return true
        if (indent <= NESTED_MEMBER_INDENT && NESTED_MEMBER.containsMatchIn(line)) return true

        return EXPLICIT_MODIFIER.containsMatchIn(line)
    }

    private const val MEMBER_INDENT = 4

    private const val NESTED_MEMBER_INDENT = 8

    private const val PARAMETER_INDENT = 8

    private val EXPLICIT_MODIFIER = Regex(
        """\b(public|protected|internal|open|abstract|sealed|override|const|lateinit)\b"""
    )

    private const val ANNOTATIONS = """^\s*(?:@\w+(?:\([^)]*\))?\s*)*"""

    private const val MODIFIERS =
        """(?:public\s+|protected\s+|internal\s+|open\s+|abstract\s+|sealed\s+|final\s+|override\s+|""" +
            """data\s+|value\s+|annotation\s+|enum\s+|inline\s+|suspend\s+|expect\s+|actual\s+|""" +
            """lateinit\s+|const\s+|external\s+|operator\s+|infix\s+|tailrec\s+|static\s+)*"""

    private val DECLARATION = Regex(
        ANNOTATIONS + MODIFIERS +
            """(?:fun|def|val|var|class|interface|object|trait|typealias|constructor|record)\b"""
    )

    private val NESTED_MEMBER = Regex(
        ANNOTATIONS + MODIFIERS + """(?:fun|def|class|interface|object|trait|constructor|record)\b"""
    )

    private val ENUM_CONSTANT = Regex("""^\s*[A-Z][A-Z0-9_]*(?:\(.*\))?\s*[,;]?\s*$""")

    private val CONST = Regex("""\bconst\b""")

    private val WHITESPACE = Regex("""\s+""")

    private val PRIVATE = Regex(ANNOTATIONS + """(?:[a-z]+\s+)*private\b""")

    private val CONSTRUCTOR_PROPERTY = Regex(
        ANNOTATIONS + """(?:[a-z]+\s+)*va[lr]\s+\w+\s*:.*,\s*$"""
    )

    private val TYPED_MEMBER = Regex(
        """^\s*(?:@\w+(?:\([^)]*\))?\s*)*""" +
            """(?:public\s+|protected\s+|static\s+|final\s+|abstract\s+|synchronized\s+|""" +
            """native\s+|default\s+|strictfp\s+|transient\s+|volatile\s+)*""" +
            """[A-Za-z_][\w.<>\[\], ?]*\s+[A-Za-z_]\w*\s*[(;=]"""
    )

    private val PARAMETER = Regex("""^\s*(?:@\w+\s*)*[A-Za-z_]\w*\s*:\s*[\w<>\[\]?., ]+,?\s*$""")
}
