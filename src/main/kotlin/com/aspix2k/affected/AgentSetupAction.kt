package com.aspix2k.affected

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Predicate
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent

private val mcpServerPlugin = PluginId.getId("com.intellij.mcpServer")

private enum class McpPlugin(val statusKey: String?) {
    AVAILABLE(null),
    NOT_INSTALLED("agent.setup.status.not.installed"),
    DISABLED("agent.setup.status.plugin.disabled"),
}

class AgentSetupAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val existing = listOf(AgentSetup.AGENTS_FILE, AgentSetup.CLAUDE_FILE)
                .filter { name -> project.basePath?.let { Files.exists(Path.of(it, name)) } == true }
                .toSet()
            val plugin = mcpPlugin()
            ApplicationManager.getApplication().invokeLater(
                { AgentSetupDialog(project, plugin, AgentSetup.targetFile(existing)).show() },
                project.disposed,
            )
        }
    }
}

private fun mcpPlugin(): McpPlugin = when {
    !PluginManager.isPluginInstalled(mcpServerPlugin) -> McpPlugin.NOT_INSTALLED
    PluginManager.getInstance().findEnabledPlugin(mcpServerPlugin) == null -> McpPlugin.DISABLED
    else -> McpPlugin.AVAILABLE
}

private class AgentSetupDialog(
    private val project: Project,
    private val plugin: McpPlugin,
    private val target: String,
) : DialogWrapper(project) {

    init {
        title = AffectedBundle.message("agent.setup.title")
        setOKButtonText(AffectedBundle.message("agent.setup.close"))
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent = panel {
        plugin.statusKey?.let { key -> row { label(AffectedBundle.message(key)) } }
        row { text(AffectedBundle.message(hintKey)) }
        row { button(AffectedBundle.message(openKey)) { openSettings() } }
        row(AffectedBundle.message("agent.setup.instructions")) {}
        row {
            textArea()
                .align(AlignX.FILL)
                .applyToComponent {
                    rows = INSTRUCTION_ROWS
                    columns = INSTRUCTION_COLUMNS
                    text = AgentSetup.instructions
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                }
        }
        row {
            button(AffectedBundle.message("agent.setup.copy")) {
                CopyPasteManager.copyTextToClipboard(AgentSetup.instructions)
            }
            if (available) {
                button(AffectedBundle.message("agent.setup.add", target)) { event ->
                    val error = addToFile()
                    if (error == null) {
                        (event.source as? JButton)?.text = AffectedBundle.message("agent.setup.added", target)
                    } else {
                        Messages.showErrorDialog(project, error, title)
                    }
                }
            }
        }
    }

    private val available = plugin == McpPlugin.AVAILABLE
    private val hintKey = if (available) "agent.setup.hint.settings" else "agent.setup.hint.plugins"
    private val openKey = if (available) "agent.setup.open.settings" else "agent.setup.open.plugins"
    private val settingsId = if (available) "com.intellij.mcpserver.settings" else "preferences.pluginManager"

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            Predicate<Configurable> { (it as? SearchableConfigurable)?.id == settingsId },
            {},
        )
    }

    private fun addToFile(): String? {
        val base = project.basePath?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }
            ?: return writeFailed(AffectedBundle.message("agent.setup.no.directory"))
        return try {
            WriteCommandAction.writeCommandAction(project)
                .withName(AffectedBundle.message("agent.setup.add", target))
                .run<IOException> {
                    val file = base.findChild(target) ?: base.createChildData(this, target)
                    VfsUtil.saveText(file, AgentSetup.merge(VfsUtilCore.loadText(file)))
                }
            null
        } catch (e: IOException) {
            writeFailed(e.message.orEmpty())
        }
    }

    private fun writeFailed(reason: String) = AffectedBundle.message("agent.setup.write.failed", target, reason)

    private companion object {
        const val INSTRUCTION_ROWS = 9
        const val INSTRUCTION_COLUMNS = 70
    }
}
