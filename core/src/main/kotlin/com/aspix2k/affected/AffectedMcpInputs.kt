package com.aspix2k.affected

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

    fun validateBaseBranch(branch: String): AffectedMcpView {
        val name = branch.trim()
        if (!branchName(name)) {
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
    ): AffectedMcpView {
        val branch = baseBranch?.let(::validateBaseBranch)
        if (branch?.error == true) return branch
        val next = AffectedMcpSettings(
            baseBranch = branch?.data?.get("baseBranch") as String? ?: current.baseBranch,
            checkConsumers = checkConsumers ?: current.checkConsumers,
            runBeforeCommit = runBeforeCommit ?: current.runBeforeCommit,
            runBeforePush = runBeforePush ?: current.runBeforePush,
            animateWhileRunning = animateWhileRunning ?: current.animateWhileRunning,
        )
        return AffectedMcpView(
            text = "Base branch: ${next.baseBranch}, consumer check: ${onOff(next.checkConsumers)}, " +
                "commit guard: ${onOff(next.runBeforeCommit)}, push guard: ${onOff(next.runBeforePush)}, " +
                "animation: ${onOff(next.animateWhileRunning)}.",
            data = mapOf(
                "baseBranch" to next.baseBranch,
                "checkConsumers" to next.checkConsumers,
                "runBeforeCommit" to next.runBeforeCommit,
                "runBeforePush" to next.runBeforePush,
                "animateWhileRunning" to next.animateWhileRunning,
            ),
        )
    }

    private fun taskName(name: String): Boolean =
        name.isNotEmpty() && name.length <= MAX_TASK_LENGTH && TASK_NAME.matches(name)

    private fun branchName(name: String): Boolean =
        name.isNotEmpty() &&
            name.length <= MAX_BRANCH_LENGTH &&
            !name.contains("..") &&
            BRANCH_NAME.matches(name)

    private fun onOff(value: Boolean): String = if (value) "on" else "off"

    private val TASK_NAME = Regex("[A-Za-z][A-Za-z0-9._-]{0,127}")
    private val BRANCH_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,254}")
    private const val MAX_TASK_LENGTH = 128
    private const val MAX_BRANCH_LENGTH = 255
}
