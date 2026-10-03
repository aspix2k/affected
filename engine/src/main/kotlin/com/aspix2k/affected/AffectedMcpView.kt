package com.aspix2k.affected

data class AffectedMcpView(
    val text: String,
    val data: Map<String, Any?>,
    val error: Boolean = false,
)

fun AffectedMcpView.withSummary(summary: RunSummary?): AffectedMcpView {
    if (error || summary == null) return this
    val saved = summary.estimatedSavedMillis?.let { " Skipped tasks took about ${formatDuration(it)} last time." }
    return copy(
        text = "$text Ran ${summary.modulesTested} of ${summary.modulesWithTests} modules with tests " +
            "in ${formatDuration(summary.durationMillis)}.${saved.orEmpty()}",
        data = data + mapOf(
            "modulesTested" to summary.modulesTested,
            "modulesWithTests" to summary.modulesWithTests,
            "durationMillis" to summary.durationMillis,
            "estimatedSavedMillis" to summary.estimatedSavedMillis,
            "skippedWithoutEstimate" to summary.skippedWithoutEstimate,
        ),
    )
}
