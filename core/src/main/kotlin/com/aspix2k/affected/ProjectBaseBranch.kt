package com.aspix2k.affected

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

@Service(Service.Level.PROJECT)
@State(name = "AffectedTestsBaseBranch", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ProjectBaseBranch : PersistentStateComponent<ProjectBaseBranch.State> {

    data class State(
        @Volatile
        var baseBranch: String = "",
    )

    @Volatile
    private var state = State()

    val configured: String?
        get() = configuredBranch(state.baseBranch, AffectedSettings.getInstance().legacyBaseBranch)

    fun configure(branch: String): Boolean {
        val previous = configured ?: AUTO_BRANCH
        val next = branch.trim().ifEmpty { AUTO_BRANCH }
        state.baseBranch = next
        return next != previous
    }

    override fun getState(): State = state

    override fun loadState(newState: State) {
        state = newState
    }

    companion object {
        const val AUTO_BRANCH = "auto"
        private const val LEGACY_DEFAULT_BRANCH = "develop"

        internal fun configuredBranch(project: String, legacy: String): String? = when {
            project == AUTO_BRANCH -> null
            project.isNotBlank() -> project
            else -> legacy.takeIf { it.isNotBlank() && it != LEGACY_DEFAULT_BRANCH }
        }
    }
}
