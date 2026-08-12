package com.aspix2k.affected.mcp

import com.aspix2k.affected.AffectedMcpView
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AffectedToolsetTest {

    @Test
    fun `every tool is classified and mutating tools are not read-only`() {
        val tools = AffectedToolset::class.java.methods.filter { it.isAnnotationPresent(McpTool::class.java) }
        val names = tools.map { it.name }.toSet()

        assertEquals(
            setOf(
                "affected_modules",
                "affected_verification_plan",
                "affected_changed_files",
                "affected_run_verification",
                "affected_run_task",
                "affected_stop",
                "affected_status",
                "affected_available_tasks",
                "affected_configure",
            ),
            names,
        )

        val readOnly = setOf(
            "affected_modules",
            "affected_verification_plan",
            "affected_changed_files",
            "affected_status",
            "affected_available_tasks",
        )
        for (method in tools) {
            val hints = method.getAnnotation(McpToolHints::class.java)
            assertTrue(hints != null, method.name)
            if (method.name in readOnly) {
                assertEquals(McpToolHintValue.TRUE, hints.readOnlyHint, method.name)
            } else {
                assertEquals(McpToolHintValue.FALSE, hints.readOnlyHint, method.name)
            }
        }
        val stop = tools.single { it.name == "affected_stop" }.getAnnotation(McpToolHints::class.java)
        assertEquals(McpToolHintValue.TRUE, stop.destructiveHint)
    }

    @Test
    fun `structured results keep the human summary and machine-readable fields`() {
        val result = AffectedMcpView(
            text = "Affected modules: 1",
            data = mapOf("modules" to listOf(":alpha"), "revision" to 3),
        ).toResult()

        assertFalse(result.isError)
        val text = result.content.single() as McpToolCallResultContent.Text
        assertEquals("Affected modules: 1", text.text)
        assertEquals("3", requireNotNull(result.structuredContent).getValue("revision").toString())
    }

    @Test
    fun `toolset stays enabled when the optional MCP server plugin is present`() {
        assertTrue(AffectedToolset().isEnabled())
    }
}
