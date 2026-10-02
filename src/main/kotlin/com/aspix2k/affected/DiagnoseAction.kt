package com.aspix2k.affected

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.progress.currentThreadCoroutineScope
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiagnoseAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        currentThreadCoroutineScope().launch {
            val report = doctorReport(AffectedDoctor.diagnose(AffectedDoctor.inspect(project)))
            withContext(Dispatchers.EDT) {
                Messages.showInfoMessage(project, report, AffectedBundle.message("dialog.doctor.title"))
            }
        }
    }
}

internal fun doctorReport(findings: List<DoctorFinding>): String = findings.joinToString("\n\n") { finding ->
    val remedyKey = if (AffectedDoctor.texts[finding.id]?.remedy == null) {
        "doctor.remedy.ok"
    } else {
        "doctor.${finding.id}.remedy"
    }
    val severity = AffectedBundle.message("doctor.severity.${finding.severity.name.lowercase()}")
    val message = finding.render(AffectedBundle.message("doctor.${finding.id}.message"))
    "$severity: $message\n→ ${AffectedBundle.message(remedyKey)}"
}
