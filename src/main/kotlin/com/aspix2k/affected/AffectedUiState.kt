package com.aspix2k.affected

internal enum class AffectedUiState(
    val canRun: Boolean,
    val canInspectModules: Boolean,
    val animated: Boolean,
    val groupTitleKey: String = "group.title",
    val runActionTextKey: String = "action.run.text",
) {
    ANALYZING(false, false, true),
    EMPTY(false, false, false),
    READY(true, true, false),
    RUNNING(false, false, true, groupTitleKey = "group.title.running"),
    UNAVAILABLE(false, false, false),
}

internal fun affectedUiState(
    snapshot: AffectedStateSnapshot,
    ideBusy: Boolean,
): AffectedUiState = affectedUiState(
    snapshot.analysisStatus,
    snapshot.verificationStatus,
    ideBusy,
    snapshot.affectedModules,
)

internal fun affectedUiState(
    analysisStatus: AnalysisStatus,
    verificationStatus: VerificationStatus,
    ideBusy: Boolean,
    affectedModules: Int,
): AffectedUiState = when {
    verificationStatus == VerificationStatus.RUNNING -> AffectedUiState.RUNNING
    ideBusy || analysisStatus == AnalysisStatus.ANALYZING -> AffectedUiState.ANALYZING
    analysisStatus == AnalysisStatus.UNAVAILABLE -> AffectedUiState.UNAVAILABLE
    affectedModules == 0 -> AffectedUiState.EMPTY
    else -> AffectedUiState.READY
}
