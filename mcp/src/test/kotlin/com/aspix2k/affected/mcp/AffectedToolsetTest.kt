package com.aspix2k.affected.mcp

import com.aspix2k.affected.AffectedMcpView
import com.aspix2k.affected.AffectedStateSnapshot
import com.aspix2k.affected.AnalysisStatus
import com.aspix2k.affected.Plan
import com.aspix2k.affected.TaskGroup
import com.aspix2k.affected.Verification
import com.aspix2k.affected.VerificationStatus
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
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
        val runTask = tools.single { it.name == "affected_run_task" }.getAnnotation(McpToolHints::class.java)
        assertEquals(McpToolHintValue.TRUE, runTask.destructiveHint)
    }

    @Test
    fun `a blocked outcome is an error with a reason and the passed flag`() {
        val plan = Plan(listOf(TaskGroup("GRADLE", "/repo", listOf(":alpha:test"))), tested = 1, compiled = 0)

        val notStarted =
            verificationView(snapshot(), Verification.Outcome(plan, false, Verification.Blocker.NOT_STARTED))
        val unresolved = verificationView(
            snapshot(),
            Verification.Outcome(Plan(emptyList(), 0, 0), false, Verification.Blocker.UNRESOLVED_CHANGES),
        )

        assertTrue(notStarted.error)
        assertEquals("not-started", notStarted.data["reason"])
        assertEquals(false, notStarted.data["passed"])
        assertTrue(unresolved.error)
        assertEquals("unresolved-changes", unresolved.data["reason"])
        assertEquals(false, unresolved.data["passed"])
    }

    @Test
    fun `the response describes the plan that ran and failure is an error`() {
        val plan = Plan(listOf(TaskGroup("GRADLE", "/repo", listOf(":alpha:test"))), tested = 1, compiled = 0)

        val passed = verificationView(snapshot(), Verification.Outcome(plan, true))
        val failed = verificationView(snapshot(), Verification.Outcome(plan, false))
        val empty = verificationView(snapshot(), Verification.Outcome(Plan(emptyList(), 0, 0), true))

        assertFalse(passed.error)
        assertEquals(listOf(":alpha:test"), passed.data["tasks"])
        assertEquals(true, passed.data["passed"])
        assertTrue(passed.text.startsWith("Passed."))
        assertTrue(failed.error)
        assertEquals(false, failed.data["passed"])
        assertTrue(failed.text.startsWith("Failed."))
        assertFalse(empty.error)
        assertEquals(true, empty.data["passed"])
    }

    @Test
    fun `settings changes invalidate every open project that is not disposed`() {
        val invalidated = mutableListOf<Project>()
        val first = project(disposed = false)
        val closed = project(disposed = true)
        val second = project(disposed = false)

        invalidateProjects(listOf(first, closed, second), invalidated::add)

        assertEquals(listOf(first, second), invalidated)
    }

    private fun snapshot() = AffectedStateSnapshot(
        revision = 1,
        analysisStatus = AnalysisStatus.READY,
        modules = emptyList(),
        verificationStatus = VerificationStatus.PREPARING,
    )

    private fun project(disposed: Boolean): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "isDisposed" -> disposed
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> error("Unexpected call ${method.name}")
        }
    } as Project

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
