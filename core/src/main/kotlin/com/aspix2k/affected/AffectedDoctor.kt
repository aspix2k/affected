package com.aspix2k.affected

import com.aspix2k.affected.build.BuildSystems
import com.aspix2k.affected.build.NestedRootCaps
import com.aspix2k.affected.build.PerformanceBudgets
import com.aspix2k.affected.build.isRegularFileNoFollow
import com.aspix2k.affected.build.nestedBuildRoots
import com.aspix2k.affected.build.resolveExecutable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.text.MessageFormat

data class BuildSystemSummary(val id: String, val modules: Int, val roots: Int)

enum class DoctorSeverity { OK, WARNING, PROBLEM }

data class DoctorSystem(val id: String, val tool: String?, val toolFound: Boolean)

data class DoctorInput(
    val snapshot: AffectedStateSnapshot,
    val projectDir: String?,
    val baseBranch: String,
    val present: List<DoctorSystem>,
    val notImported: List<String>,
    val rootsCapped: Boolean,
    val ideBusy: Boolean,
)

data class DoctorText(val message: String, val remedy: String? = null)

data class DoctorFinding(val id: String, val severity: DoctorSeverity, val args: List<String> = emptyList()) {
    val message: String get() = render(requireNotNull(AffectedDoctor.texts[id]).message)
    val remedy: String get() = AffectedDoctor.texts[id]?.remedy?.let(::render) ?: NOTHING_TO_DO

    fun render(pattern: String): String = MessageFormat(pattern).format(args.toTypedArray())
}

private const val NOTHING_TO_DO = "Nothing to do."

object AffectedDoctor {

    val texts: Map<String, DoctorText> = mapOf(
        "git-unavailable" to DoctorText(
            "The project directory {0} is not a git repository, or git cannot be started.",
            "Open a project inside a git repository and make sure git is on the IDE PATH.",
        ),
        "base-resolved" to DoctorText("Changes are compared against {0}."),
        "base-unresolved" to DoctorText(
            "No comparison base was found: {0} and the fallback branches exist neither locally nor on origin.",
            "Fetch the base branch from origin, or set a base branch that exists.",
        ),
        "build-system-not-imported" to DoctorText(
            "{0} build files exist in the project, but the project is not imported in the IDE.",
            "Import or link the project in the IDE so Affected can read its modules.",
        ),
        "no-build-system" to DoctorText(
            "No supported build system was detected in this project.",
            "Open the project root that contains a build file such as settings.gradle.kts, pom.xml or Cargo.toml.",
        ),
        "build-systems" to DoctorText("Build systems found (id, modules / roots): {0}."),
        "tools-missing" to DoctorText(
            "These tools were not found on the IDE PATH: {0}.",
            "Install them, or start the IDE from a shell where they are on PATH.",
        ),
        "tools-ready" to DoctorText("Tools found on the IDE PATH: {0}."),
        "nested-roots-capped" to DoctorText(
            "Discovery of nested build roots hit its limit ({0} roots at most) and may have skipped some.",
            "Open the specific subproject you work on, so fewer directories are scanned.",
        ),
        "modules-over-budget" to DoctorText(
            "The analysis found more than {0} affected modules and was dropped.",
            "Narrow the change, or compare against a closer base branch.",
        ),
        "changes-found" to DoctorText("Changed files: {0}."),
        "changes-unresolved" to DoctorText(
            "{0} changed files are owned by no module that can be verified, for example {1}.",
            "Add the files to a build module with tests or a compile task, then run Affected again.",
        ),
        "changes-uncovered" to DoctorText(
            "{0} changed code files belong to no detected build system and are not checked, for example {1}.",
            "Add the build file for that language, or check those files by hand.",
        ),
        "analysis-analyzing" to DoctorText(
            "Affected is still analyzing the current revision.",
            "Wait a moment and diagnose again.",
        ),
        "analysis-unavailable" to DoctorText(
            "The analysis failed and no result is available.",
            "Check the IDE log for Affected warnings, then change a file or refresh to retry.",
        ),
        "analysis-ready" to DoctorText("The analysis is ready."),
        "ide-busy" to DoctorText(
            "The IDE is indexing or importing a project.",
            "Wait until the background tasks finish, then run Affected.",
        ),
    )

    suspend fun inspect(project: Project): DoctorInput = runInterruptible(Dispatchers.IO) {
        val projectDir = project.basePath
        val present = BuildSystems.of(project)
        val presentIds = present.mapTo(HashSet()) { it.id }
        DoctorInput(
            snapshot = project.service<AffectedState>().snapshot(),
            projectDir = projectDir,
            baseBranch = AffectedSettings.getInstance().baseBranch,
            present = present.map { system ->
                val tool = CLI_EXECUTABLES[system.id]
                DoctorSystem(system.id, tool, tool != null && resolveExecutable(tool) != tool)
            },
            notImported = projectDir?.let(::File)?.let { dir ->
                IDE_MODEL_MARKERS.filter { (id, names) -> id !in presentIds && hasMarkerFile(dir, names) }.keys.toList()
            }.orEmpty(),
            rootsCapped = projectDir?.let(NestedRootCaps::reachedUnder) == true,
            ideBusy = projectBusy(project),
        )
    }

    fun diagnose(input: DoctorInput): List<DoctorFinding> {
        val snapshot = input.snapshot
        val changes = snapshot.changes
        val checked = input.present.filter { it.tool != null }
        val missing = checked.filterNot(DoctorSystem::toolFound)
        val summaries = snapshot.systems.associateBy(BuildSystemSummary::id)
        return listOfNotNull(
            changes?.takeUnless { it.gitUsable }?.let {
                DoctorFinding("git-unavailable", DoctorSeverity.PROBLEM, listOf(input.projectDir.orEmpty()))
            },
            changes?.takeIf { it.gitUsable }?.resolvedBranch?.let {
                DoctorFinding("base-resolved", DoctorSeverity.OK, listOf(it))
            },
            changes?.takeIf { it.gitUsable && it.baseUnresolved }?.let {
                DoctorFinding("base-unresolved", DoctorSeverity.PROBLEM, listOf(input.baseBranch))
            },
        ) + input.notImported.map { DoctorFinding("build-system-not-imported", DoctorSeverity.PROBLEM, listOf(it)) } +
            listOfNotNull(
                DoctorFinding("no-build-system", DoctorSeverity.PROBLEM)
                    .takeIf { input.present.isEmpty() && input.notImported.isEmpty() },
                DoctorFinding(
                    "build-systems",
                    DoctorSeverity.OK,
                    listOf(
                        input.present.joinToString { system ->
                            val summary = summaries[system.id]
                            "${system.id} ${summary?.modules ?: 0} / ${summary?.roots ?: 0}"
                        },
                    ),
                ).takeIf { input.present.isNotEmpty() },
                DoctorFinding(
                    "tools-missing",
                    DoctorSeverity.PROBLEM,
                    listOf(missing.joinToString { it.tool.orEmpty() }),
                ).takeIf { missing.isNotEmpty() },
                DoctorFinding("tools-ready", DoctorSeverity.OK, listOf(checked.joinToString { it.tool.orEmpty() }))
                    .takeIf { checked.isNotEmpty() && missing.isEmpty() },
                DoctorFinding(
                    "nested-roots-capped",
                    DoctorSeverity.WARNING,
                    listOf(PerformanceBudgets.MAX_NESTED_ROOTS.toString()),
                ).takeIf { input.rootsCapped },
                DoctorFinding(
                    "modules-over-budget",
                    DoctorSeverity.PROBLEM,
                    listOf(MAX_PUBLISHED_MODULES.toString()),
                ).takeIf { snapshot.overBudget },
                changes?.let {
                    DoctorFinding("changes-found", DoctorSeverity.OK, listOf(it.files.size.toString()))
                },
                snapshot.plans?.testsOnly?.unresolved?.takeIf { it.isNotEmpty() }?.let {
                    examples("changes-unresolved", it, input.projectDir)
                },
                changes?.uncovered?.takeIf { it.isNotEmpty() }?.let {
                    examples("changes-uncovered", it, input.projectDir)
                },
                analysis(snapshot),
                DoctorFinding("ide-busy", DoctorSeverity.WARNING).takeIf { input.ideBusy },
            )
    }

    fun view(findings: List<DoctorFinding>): AffectedMcpView {
        val worst = findings.maxOfOrNull { it.severity } ?: DoctorSeverity.OK
        val problems = findings.count { it.severity == DoctorSeverity.PROBLEM }
        val warnings = findings.count { it.severity == DoctorSeverity.WARNING }
        return AffectedMcpView(
            text = "Doctor: $problems problem(s), $warnings warning(s).\n" + findings.joinToString("\n") {
                "[${it.severity.name.lowercase()}] ${it.message} ${it.remedy}"
            },
            data = mapOf(
                "status" to worst.name.lowercase(),
                "problems" to problems,
                "warnings" to warnings,
                "findings" to findings.map {
                    mapOf(
                        "id" to it.id,
                        "severity" to it.severity.name.lowercase(),
                        "message" to it.message,
                        "remedy" to it.remedy,
                    )
                },
            ),
        )
    }

    private fun analysis(snapshot: AffectedStateSnapshot): DoctorFinding? = when (snapshot.analysisStatus) {
        AnalysisStatus.ANALYZING -> DoctorFinding("analysis-analyzing", DoctorSeverity.WARNING)
        AnalysisStatus.UNAVAILABLE ->
            DoctorFinding("analysis-unavailable", DoctorSeverity.PROBLEM).takeUnless { snapshot.overBudget }
        AnalysisStatus.READY -> DoctorFinding("analysis-ready", DoctorSeverity.OK)
    }

    private fun examples(id: String, files: List<File>, projectDir: String?): DoctorFinding {
        val root = projectDir?.let(::File)
        val sample = files.take(MAX_EXAMPLES).joinToString {
            root?.takeIf { base -> it.startsWith(base) }?.let(it::relativeTo)?.invariantSeparatorsPath
                ?: it.invariantSeparatorsPath
        }
        return DoctorFinding(id, DoctorSeverity.WARNING, listOf(files.size.toString(), sample))
    }

    private fun hasMarkerFile(directory: File, names: Set<String>): Boolean =
        nestedBuildRoots(directory, names::contains) { root -> names.any { File(root, it).isRegularFileNoFollow() } }
            .isNotEmpty()

    private const val MAX_EXAMPLES = 5

    private val IDE_MODEL_MARKERS = mapOf(
        "GRADLE" to setOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts"),
        "MAVEN" to setOf("pom.xml"),
    )

    private val CLI_EXECUTABLES = mapOf(
        "CARGO" to "cargo",
        "GO" to "go",
        "DOTNET" to "dotnet",
        "DART" to "dart",
        "FLUTTER" to "flutter",
        "DENO" to "deno",
        "SWIFT" to "swift",
        "XCODE" to "xcodebuild",
        "BAZEL" to "bazel",
        "BUCK2" to "buck2",
        "PANTS" to "pants",
        "SBT" to "sbt",
        "MAKE" to "make",
        "NINJA" to "ninja",
        "MESON" to "meson",
        "ANT" to "ant",
        "CMAKE" to "cmake",
        "DBT" to "dbt",
        "SQLC" to "sqlc",
        "ATLAS" to "atlas",
        "RPROJECT" to "Rscript",
        "COMPOSER" to "php",
    )
}
