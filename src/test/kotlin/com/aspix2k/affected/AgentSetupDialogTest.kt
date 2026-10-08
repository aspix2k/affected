package com.aspix2k.affected

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import javax.swing.JButton

class AgentSetupDialogTest : BasePlatformTestCase() {

    fun testTheDialogOpensForEveryStateOfTheMcpServerPlugin() {
        McpPlugin.entries.forEach { plugin ->
            runInEdt {
                val dialog = AgentSetupDialog(project, plugin, "AGENTS.md")
                Disposer.register(testRootDisposable, dialog.disposable)
                val panel = dialog.createCenterPanel()
                val buttons = UIUtil.findComponentsOfType(panel, JButton::class.java).map { it.text }
                val available = plugin == McpPlugin.AVAILABLE
                val settings = if (available) "agent.setup.open.settings" else "agent.setup.open.plugins"

                assertTrue("${plugin.name}: $buttons", AffectedBundle.message(settings) in buttons)
                assertEquals(
                    plugin.name,
                    available,
                    AffectedBundle.message("agent.setup.add", "AGENTS.md") in buttons,
                )
                assertEquals(
                    AgentSetup.instructions,
                    UIUtil.findComponentsOfType(panel, JBTextArea::class.java).single().text,
                )
            }
        }
    }
}
