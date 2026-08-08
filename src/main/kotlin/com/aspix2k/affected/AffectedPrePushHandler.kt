package com.aspix2k.affected

import com.intellij.dvcs.push.PrePushHandler
import com.intellij.dvcs.push.PushInfo
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project

/**
 * Stops a push whose affected verification failed. Off by default: it is the
 * user's call whether a push waits for a test run.
 */
class AffectedPrePushHandler : PrePushHandler {

    override fun getPresentableName(): String = AffectedBundle.message("push.handler.name")

    override fun handle(
        project: Project,
        pushDetails: List<PushInfo>,
        indicator: ProgressIndicator,
    ): PrePushHandler.Result {
        if (!AffectedSettings.getInstance().runBeforePush) return PrePushHandler.Result.OK

        indicator.text = AffectedBundle.message("progress.title")
        val plan = Verification.plan(project)
        if (plan.isEmpty) return PrePushHandler.Result.OK

        indicator.text = AffectedBundle.message("push.handler.running", plan.tested, plan.compiled)
        val outcome = Verification.runAndWait(project, plan)

        return if (outcome.passed) PrePushHandler.Result.OK else PrePushHandler.Result.ABORT
    }
}
