package com.aspix2k.affected

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File

private val LOG = logger<Verification>()

internal suspend fun verifyAndReport(
    project: Project,
    onPrepared: (Verification.Prepared) -> Unit = {},
): Boolean {
    val prepared = try {
        Verification.prepare(project, guard = true)
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
    reportOutcome(project, prepared, outcome)
    return outcome.passed
}

internal fun reportOutcome(project: Project, prepared: Verification.Prepared, outcome: Verification.Outcome) {
    reportBlocker(project, prepared, outcome.blocker)
    outcome.summary?.let {
        notifyAffected(
            project,
            AffectedBundle.message("notification.summary.title"),
            runSummaryText(it),
            NotificationType.INFORMATION,
        )
    }
}

internal fun runSummaryText(summary: RunSummary): String {
    val covered = AffectedBundle.message(
        "notification.summary.text",
        summary.modulesTested,
        summary.modulesWithTests,
        formatDuration(summary.durationMillis),
    )
    val saved = summary.estimatedSavedMillis?.let { millis ->
        if (summary.skippedWithoutEstimate == 0) {
            AffectedBundle.message("notification.summary.saved", formatDuration(millis))
        } else {
            AffectedBundle.message(
                "notification.summary.saved.partial",
                formatDuration(millis),
                summary.skippedWithoutEstimate,
            )
        }
    }
    return listOfNotNull(covered, saved).joinToString(" ")
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
            AffectedBundle.message("notification.no.base.text"),
            NotificationType.WARNING,
        )
        Verification.Blocker.NOT_STARTED -> notifyNotStarted(project)
        null -> if (prepared.uncovered.isNotEmpty()) {
            notifyAffected(
                project,
                AffectedBundle.message("notification.uncovered.title"),
                AffectedBundle.message(
                    "notification.uncovered.text",
                    prepared.uncovered.size,
                    uncoveredExtensions(prepared.uncovered),
                ),
                NotificationType.INFORMATION,
            )
        }
    }
}

internal fun notifyNotStarted(project: Project) = notifyAffected(
    project,
    AffectedBundle.message("notification.busy.title"),
    AffectedBundle.message("action.run.description.busy"),
    NotificationType.WARNING,
)

internal fun uncoveredExtensions(files: List<File>): String =
    files.mapTo(sortedSetOf()) { ".${it.extension.lowercase()}" }.take(MAX_LISTED_EXTENSIONS).joinToString()

private const val MAX_LISTED_EXTENSIONS = 5

internal fun notifyAffected(project: Project, title: String, content: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("AffectedTests")
        .createNotification(title, content, type)
        .notify(project)
}
