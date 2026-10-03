package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

data class GroupResult(val group: TaskGroup, val passed: Boolean)

data class VerificationRecord(val results: List<GroupResult>, val changes: BuildChanges) {

    val failed: List<TaskGroup> get() = results.filterNot(GroupResult::passed).map(GroupResult::group)
}

enum class BaseVerdict(val id: String) {
    REGRESSION("regression"),
    PRE_EXISTING("failing-before-change"),
    UNKNOWN("unknown"),
}

enum class BaseNotRun(val id: String) {
    NO_BASE_COMMIT("no-base-commit"),
    CHECKOUT_FAILED("checkout-failed"),
    MODULE_MISSING("module-missing"),
    TIMED_OUT("timed-out"),
    STOPPED("stopped"),
    UNSUPPORTED_SYSTEM("not-supported-yet"),
    GRADLE_JVM_DIFFERS("gradle-jvm-differs"),
}

sealed interface BaseRun {
    data class Finished(val passed: Boolean) : BaseRun

    data class Skipped(val reason: BaseNotRun) : BaseRun
}

data class BaseGroupVerdict(val systemId: String, val root: String, val tasks: List<String>, val run: BaseRun) {

    val verdict: BaseVerdict
        get() = when (run) {
            is BaseRun.Finished -> if (run.passed) BaseVerdict.REGRESSION else BaseVerdict.PRE_EXISTING
            is BaseRun.Skipped -> BaseVerdict.UNKNOWN
        }

    val reason: BaseNotRun? get() = (run as? BaseRun.Skipped)?.reason
}

enum class BaseCheckBlocker { NO_FAILED_VERIFICATION, NOT_STARTED }

data class BaseCheckReport(
    val verdicts: List<BaseGroupVerdict> = emptyList(),
    val blocker: BaseCheckBlocker? = null,
    val baseCommit: String? = null,
    val baseBranch: String? = null,
) {

    val comparison: String?
        get() = baseCommit?.let { listOfNotNull(baseBranch, it.take(SHORT_COMMIT)).joinToString(" ") }
}

private const val SHORT_COMMIT = 12

fun translateToBase(path: String, projectRoot: String, baseProjectRoot: String): String? = runCatching {
    val project = Path.of(projectRoot).toAbsolutePath().normalize()
    val target = Path.of(path).toAbsolutePath().normalize()
    if (target.startsWith(project)) {
        Path.of(baseProjectRoot).resolve(project.relativize(target)).invariantSeparatorsPathString
    } else {
        null
    }
}.getOrNull()

fun relativeToProject(path: String, projectRoot: String): String = runCatching {
    val relative = Path.of(projectRoot).toAbsolutePath().normalize()
        .relativize(Path.of(path).toAbsolutePath().normalize())
        .invariantSeparatorsPathString
    relative.ifEmpty { "." }
}.getOrDefault(path)

fun BuildChanges.translatedToBase(projectRoot: String, baseProjectRoot: String): BuildChanges = copy(
    files = files.mapNotNull { translateToBase(it, projectRoot, baseProjectRoot) },
    exactSelectionEligible = exactSelectionEligible
        .mapNotNullTo(HashSet()) { translateToBase(it, projectRoot, baseProjectRoot) },
)
