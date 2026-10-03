package com.aspix2k.affected

import com.aspix2k.affected.build.ruby.supports
import java.nio.file.Files
import java.nio.file.Path

object AffectedMcpInputs {

    fun validateNamedTask(snapshot: AffectedStateSnapshot, task: String): AffectedMcpView {
        AffectedMcpViews.notReady(snapshot)?.let { return it }
        val name = task.trim()
        if (!taskName(name)) {
            return AffectedMcpView(
                text = "Task name is invalid.",
                data = mapOf("reason" to "invalid-task"),
                error = true,
            )
        }
        val supported = snapshot.modules.filter { it.supports(name) }
        if (supported.isEmpty()) {
            return AffectedMcpView(
                text = "No affected module declares task '$name'.",
                data = mapOf("reason" to "unknown-task", "task" to name),
                error = true,
            )
        }
        return AffectedMcpView(
            text = "Task '$name' is available on ${supported.size} module(s).",
            data = mapOf("task" to name, "modules" to supported.map(AffectedModule::id)),
        )
    }

    fun validateSourceFile(basePath: String, path: String): AffectedMcpView {
        val requested = path.trim()
        val base = runCatching { Path.of(basePath).toRealPath() }.getOrNull()
            ?: return sourceFileError("no-base-path", "Project base path is unavailable.")
        val resolved = runCatching { base.resolve(requested).normalize() }.getOrNull()
        if (requested.isEmpty() || requested.length > MAX_PATH_LENGTH || resolved == null) {
            return sourceFileError("invalid-path", "File path is invalid.")
        }
        val real = runCatching { resolved.toRealPath() }.getOrNull()
            ?.takeIf { Files.isRegularFile(it) }
            ?: return sourceFileError("file-not-found", "File does not exist.")
        if (!real.startsWith(base)) return sourceFileError("outside-project", "File is outside the project.")
        if (real.fileName.toString().substringAfterLast('.', "") !in SOURCE_EXTENSIONS) {
            return sourceFileError("unsupported-file", "Only .kt and .java files can be looked up.")
        }
        val relative = base.relativize(real).joinToString("/")
        return AffectedMcpView(
            text = "File: $relative",
            data = mapOf("file" to relative, "path" to real.toString()),
        )
    }

    fun validateBaseBranch(branch: String): AffectedMcpView {
        val name = branch.trim().ifEmpty { ProjectBaseBranch.AUTO_BRANCH }
        if (name != ProjectBaseBranch.AUTO_BRANCH && !branchName(name)) {
            return AffectedMcpView(
                text = "Base branch is invalid.",
                data = mapOf("reason" to "invalid-branch"),
                error = true,
            )
        }
        return AffectedMcpView(
            text = "Base branch: $name",
            data = mapOf("baseBranch" to name),
        )
    }

    fun applySettings(
        current: AffectedMcpSettings,
        baseBranch: String? = null,
        checkConsumers: Boolean? = null,
        runBeforeCommit: Boolean? = null,
        runBeforePush: Boolean? = null,
        animateWhileRunning: Boolean? = null,
        testDependents: Boolean? = null,
    ): AffectedMcpView {
        val chosenBranch = if (baseBranch == null) {
            current.baseBranch
        } else {
            val validated = validateBaseBranch(baseBranch)
            if (validated.error) return validated
            validated.data.getValue("baseBranch") as String
        }
        val next = AffectedMcpSettings(
            baseBranch = chosenBranch,
            resolvedBaseBranch = current.resolvedBaseBranch.takeIf { chosenBranch == current.baseBranch },
            checkConsumers = checkConsumers ?: current.checkConsumers,
            runBeforeCommit = runBeforeCommit ?: current.runBeforeCommit,
            runBeforePush = runBeforePush ?: current.runBeforePush,
            animateWhileRunning = animateWhileRunning ?: current.animateWhileRunning,
            testDependents = testDependents ?: current.testDependents,
        )
        return AffectedMcpView(
            text = "Base branch: ${next.baseBranchLabel}, consumer check: ${onOff(next.checkConsumers)}, " +
                "dependents' tests: ${onOff(next.testDependents)}, " +
                "commit guard: ${onOff(next.runBeforeCommit)}, push guard: ${onOff(next.runBeforePush)}, " +
                "animation: ${onOff(next.animateWhileRunning)}.",
            data = mapOf(
                "baseBranch" to next.baseBranch,
                "resolvedBaseBranch" to next.resolvedBaseBranch,
                "checkConsumers" to next.checkConsumers,
                "testDependents" to next.testDependents,
                "runBeforeCommit" to next.runBeforeCommit,
                "runBeforePush" to next.runBeforePush,
                "animateWhileRunning" to next.animateWhileRunning,
            ),
        )
    }

    private fun sourceFileError(reason: String, text: String) = AffectedMcpView(
        text = text,
        data = mapOf("reason" to reason),
        error = true,
    )

    private fun taskName(name: String): Boolean = TASK_NAME.matches(name)

    private fun branchName(name: String): Boolean =
        !name.contains("..") && BRANCH_NAME.matches(name)

    private fun onOff(value: Boolean): String = if (value) "on" else "off"

    private val SOURCE_EXTENSIONS = setOf("kt", "java")
    private const val MAX_PATH_LENGTH = 4096
    private val TASK_NAME = Regex("[A-Za-z][A-Za-z0-9._-]{0,127}")
    private val BRANCH_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,254}")
}
