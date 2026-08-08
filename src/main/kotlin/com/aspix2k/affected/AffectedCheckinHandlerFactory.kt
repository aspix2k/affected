package com.aspix2k.affected

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.ui.RefreshableOnComponent
import com.intellij.ui.NonFocusableCheckBox
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

class AffectedCheckinHandlerFactory : CheckinHandlerFactory() {

    override fun createHandler(panel: CheckinProjectPanel, context: CommitContext): CheckinHandler =
        AffectedCheckinHandler(panel)
}

private class AffectedCheckinHandler(private val panel: CheckinProjectPanel) : CheckinHandler() {

    override fun getBeforeCheckinConfigurationPanel(): RefreshableOnComponent {
        val checkBox = NonFocusableCheckBox(AffectedBundle.message("checkin.run.text"))
        checkBox.toolTipText = AffectedBundle.message("checkin.run.description")

        return object : RefreshableOnComponent {
            override fun getComponent(): JComponent = JBUI.Panels.simplePanel(checkBox)

            override fun saveState() {
                AffectedSettings.getInstance().runBeforeCommit = checkBox.isSelected
            }

            override fun restoreState() {
                checkBox.isSelected = AffectedSettings.getInstance().runBeforeCommit
            }
        }
    }

    override fun beforeCheckin(): ReturnResult {
        if (!AffectedSettings.getInstance().runBeforeCommit) return ReturnResult.COMMIT

        val project = panel.project
        val outcome = ProgressManager.getInstance().runProcessWithProgressSynchronously<Verification.Outcome, Nothing>(
            {
                runBlockingCancellable {
                    val plan = Verification.plan(project)
                    Verification.runAndWait(project, plan)
                }
            },
            AffectedBundle.message("progress.title"),
            true,
            project,
        )

        return if (outcome.passed) ReturnResult.COMMIT else ReturnResult.CANCEL
    }
}
