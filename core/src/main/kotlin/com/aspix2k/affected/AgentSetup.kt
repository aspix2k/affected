package com.aspix2k.affected

object AgentSetup {
    const val RUN_VERIFICATION = "affected_run_verification"
    const val VERIFICATION_PLAN = "affected_verification_plan"
    const val CHANGED_FILES = "affected_changed_files"
    const val AGENTS_FILE = "AGENTS.md"
    const val CLAUDE_FILE = "CLAUDE.md"
    const val START_MARKER = "<!-- affected:agent-setup:start -->"
    const val END_MARKER = "<!-- affected:agent-setup:end -->"

    val instructions = """
        ## Verifying changes with Affected

        The Affected IDE plugin exposes MCP tools through the JetBrains MCP Server.

        - Before finishing a task, call `$RUN_VERIFICATION` and wait for the result.
        - Treat `passed: false` as a failed task: read the `reason` and the failing output, fix it and call the tool again.
        - Call `$VERIFICATION_PLAN` to see what will run and `$CHANGED_FILES` to see what counts as changed.
        - Pass the absolute project directory as `projectPath`; the server asks for it when it cannot tell the project.
        - Do not run the whole test suite while these tools are available.
    """.trimIndent()

    val referencedTools: Set<String> = Regex("affected_[a-z_]+").findAll(instructions).map { it.value }.toSet()

    fun targetFile(existing: Set<String>): String =
        if (AGENTS_FILE !in existing && CLAUDE_FILE in existing) CLAUDE_FILE else AGENTS_FILE

    fun merge(existing: String, instructions: String = this.instructions): String {
        val section = "$START_MARKER\n$instructions\n$END_MARKER"
        val start = existing.indexOf(START_MARKER)
        val end = existing.indexOf(END_MARKER, start + 1)
        return when {
            existing.isBlank() -> "$section\n"
            start >= 0 && end > start -> existing.replaceRange(start, end + END_MARKER.length, section)
            else -> "${existing.trimEnd()}\n\n$section\n"
        }
    }
}
