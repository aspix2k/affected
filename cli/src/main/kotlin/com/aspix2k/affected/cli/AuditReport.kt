package com.aspix2k.affected.cli

import com.aspix2k.affected.DeclaredDependency
import com.aspix2k.affected.EngineAudit
import com.aspix2k.affected.EnginePlan
import com.google.gson.GsonBuilder
import java.io.File

internal fun writeFullRunReport(report: File, plan: EnginePlan, audit: EngineAudit, arguments: Arguments) = write(
    report,
    mapOf(
        "version" to REPORT_VERSION,
        "mode" to "full-run",
        "base" to arguments.baseBranch,
        "blocker" to plan.blocker?.name,
        "failedPlannedGroups" to audit.failedInSelection.size,
        "plannedTasks" to plan.plan.groups.sumOf { it.tasks.size },
        "everyTestTasks" to plan.everyTest.groups.sumOf { it.tasks.size },
        "plannedMillis" to audit.selectionMillis,
        "fullRunMillis" to audit.fullRunMillis,
        "missed" to audit.missed.map { group ->
            mapOf(
                "system" to group.systemId,
                "root" to relativeRoot(group, arguments),
                "tasks" to group.tasks,
            )
        },
    ),
)

internal fun writeBrokenFilesReport(
    report: File,
    base: String,
    verdicts: Map<String, String>,
    missed: List<DeclaredDependency>,
) =
    write(
        report,
        mapOf(
            "version" to REPORT_VERSION,
            "mode" to "broken-files",
            "base" to base,
            "verdicts" to verdicts.values.groupingBy { it }.eachCount(),
            "files" to verdicts.map { (path, verdict) -> mapOf("path" to path, "verdict" to verdict) },
            "missed" to missed.map {
                mapOf("path" to it.path, "system" to it.system, "root" to it.root, "module" to it.module)
            },
        ),
    )

private fun write(report: File, content: Map<String, Any?>) {
    report.parentFile.mkdirs()
    report.writeText(GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(content) + "\n")
}

private const val REPORT_VERSION = 1
