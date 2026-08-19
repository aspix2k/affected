package com.aspix2k.affected

import com.intellij.dvcs.push.PrePushHandler
import com.intellij.dvcs.push.PushInfo
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project

class AffectedPrePushHandler : PrePushHandler {

    override fun getPresentableName(): String = AffectedBundle.message("push.handler.name")

    override fun handle(
        project: Project,
        pushDetails: List<PushInfo>,
        indicator: ProgressIndicator,
    ): PrePushHandler.Result {
        if (!AffectedSettings.getInstance().runBeforePush) return PrePushHandler.Result.OK

        return runBlockingCancellable {
            indicator.text = AffectedBundle.message("progress.title")
            val prepared = Verification.prepare(project)
            val plan = prepared.plan
            if (plan.isEmpty) {
                return@runBlockingCancellable if (verificationPassesWithoutWork(prepared)) {
                    PrePushHandler.Result.OK
                } else {
                    PrePushHandler.Result.ABORT
                }
            }

            indicator.text = AffectedBundle.message("push.handler.running", plan.tested, plan.compiled)
            val outcome = Verification.runAndWait(project, prepared)

            if (outcome.passed) PrePushHandler.Result.OK else PrePushHandler.Result.ABORT
        }
    }
}
