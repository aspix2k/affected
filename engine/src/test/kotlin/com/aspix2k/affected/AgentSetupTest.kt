package com.aspix2k.affected

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentSetupTest {

    private val section = "${AgentSetup.START_MARKER}\n${AgentSetup.instructions}\n${AgentSetup.END_MARKER}"

    @Test
    fun `creates the section in an empty file`() {
        assertEquals("$section\n", AgentSetup.merge(""))
    }

    @Test
    fun `appends the section after existing content`() {
        assertEquals("# Rules\n\nBe kind.\n\n$section\n", AgentSetup.merge("# Rules\n\nBe kind.\n\n"))
    }

    @Test
    fun `replaces the content between the markers`() {
        val existing = "# Rules\n\n${AgentSetup.START_MARKER}\nold\n${AgentSetup.END_MARKER}\n\nTail\n"

        assertEquals("# Rules\n\n$section\n\nTail\n", AgentSetup.merge(existing))
    }

    @Test
    fun `a second run changes nothing`() {
        val once = AgentSetup.merge("# Rules\n")

        assertEquals(once, AgentSetup.merge(once))
        assertEquals(1, Regex(Regex.escape(AgentSetup.START_MARKER)).findAll(once).count())
    }

    @Test
    fun `an unterminated start marker is not treated as a section`() {
        val merged = AgentSetup.merge("${AgentSetup.START_MARKER}\nhand written\n")

        assertTrue(merged.startsWith("${AgentSetup.START_MARKER}\nhand written\n\n$section"))
    }

    @Test
    fun `prefers CLAUDE md only when AGENTS md is absent`() {
        assertEquals("AGENTS.md", AgentSetup.targetFile(emptySet()))
        assertEquals("CLAUDE.md", AgentSetup.targetFile(setOf("CLAUDE.md")))
        assertEquals("AGENTS.md", AgentSetup.targetFile(setOf("CLAUDE.md", "AGENTS.md")))
        assertEquals("AGENTS.md", AgentSetup.targetFile(setOf("AGENTS.md")))
    }

    @Test
    fun `instructions reference the verification tools`() {
        assertEquals(
            setOf(AgentSetup.RUN_VERIFICATION, AgentSetup.VERIFICATION_PLAN, AgentSetup.CHANGED_FILES),
            AgentSetup.referencedTools,
        )
    }
}
