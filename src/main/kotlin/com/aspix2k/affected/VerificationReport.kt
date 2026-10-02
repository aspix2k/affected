package com.aspix2k.affected

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

private val LOG = logger<Verification>()

internal suspend fun verifyAndReport(
    project: Project,
    onPrepared: (Verification.Prepared) -> Unit = {},
): Boolean {
    val prepared = try {
        Verification.prepare(project)
    } catch (error: ChangeAnalyzer.GitFailure) {
        LOG.warn("Could not analyze changes", error)
        notifyAffected(
            project,
            AffectedBundle.message("notification.analysis.failed.title"),
            AffectedBundle.message("notification.analysis.failed.text"),
            NotificationType.WARNING,
        )
        return false
    }
    onPrepared(prepared)
    val outcome = Verification.runAndWait(project, prepared)
    reportBlocker(project, prepared, outcome.blocker)
    return outcome.passed
}

internal fun reportBlocker(project: Project, prepared: Verification.Prepared, blocker: Verification.Blocker?) {
    when (blocker) {
        Verification.Blocker.UNRESOLVED_CHANGES -> notifyAffected(
            project,
            AffectedBundle.message("notification.unresolved.title"),
            if (prepared.unresolvedFiles in 1 until prepared.changedFiles) {
                AffectedBundle.message(
                    "notification.unresolved.partial.text",
                    prepared.unresolvedFiles,
                    prepared.changedFiles,
                )
            } else {
                AffectedBundle.message("notification.unresolved.text", prepared.changedFiles)
            },
            NotificationType.WARNING,
        )
        Verification.Blocker.NO_COMPARISON_BASE -> notifyAffected(
            project,
            AffectedBundle.message("notification.no.base.title"),
            AffectedBundle.message("notification.no.base.text", AffectedSettings.getInstance().baseBranch),
            NotificationType.WARNING,
        )
        Verification.Blocker.NOT_STARTED -> notifyAffected(
            project,
            AffectedBundle.message("notification.busy.title"),
            AffectedBundle.message("action.run.description.busy"),
            NotificationType.WARNING,
        )
        null -> Unit
    }
}

internal fun notifyAffected(project: Project, title: String, content: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("AffectedTests")
        .createNotification(title, content, type)
        .notify(project)
}
