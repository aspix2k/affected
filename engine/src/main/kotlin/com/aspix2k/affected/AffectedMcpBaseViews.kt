package com.aspix2k.affected

object AffectedMcpBaseViews {

    fun check(report: BaseCheckReport): AffectedMcpView = when (report.blocker) {
        BaseCheckBlocker.NO_FAILED_VERIFICATION -> AffectedMcpView(
            text = "No failed verification to check. Run the verification first.",
            data = mapOf("reason" to "no-failed-verification"),
            error = true,
        )
        BaseCheckBlocker.NOT_STARTED -> AffectedMcpView(
            text = "Affected cannot start the base check until the current exclusive session finishes.",
            data = mapOf("reason" to "busy"),
            error = true,
        )
        null -> completed(report)
    }

    private fun completed(report: BaseCheckReport): AffectedMcpView {
        val counts = BaseVerdict.entries.associateWith { verdict -> report.verdicts.count { it.verdict == verdict } }
        return AffectedMcpView(
            text = "Compared with the base${report.comparison?.let { " ($it)" }.orEmpty()}: " +
                "${counts.getValue(BaseVerdict.REGRESSION)} regression(s), " +
                "${counts.getValue(BaseVerdict.PRE_EXISTING)} failing before your change, " +
                "${counts.getValue(BaseVerdict.UNKNOWN)} unknown.",
            data = mapOf(
                "baseBranch" to report.baseBranch,
                "baseCommit" to report.baseCommit,
                "regressions" to counts.getValue(BaseVerdict.REGRESSION),
                "failingBefore" to counts.getValue(BaseVerdict.PRE_EXISTING),
                "unknown" to counts.getValue(BaseVerdict.UNKNOWN),
                "groups" to report.verdicts.map { group ->
                    mapOf(
                        "system" to group.systemId,
                        "root" to group.root,
                        "tasks" to group.tasks,
                        "verdict" to group.verdict.id,
                        "reason" to group.reason?.id,
                    )
                },
            ),
        )
    }
}
