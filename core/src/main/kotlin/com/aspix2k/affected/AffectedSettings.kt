package com.aspix2k.affected

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "AffectedTestsSettings", storages = [Storage("affected-tests.xml")])
class AffectedSettings : PersistentStateComponent<AffectedSettings.State> {

    data class State(
        @Volatile
        var baseBranch: String = "develop",
        @Volatile
        var checkConsumers: Boolean = false,
        @Volatile
        var animateWhileRunning: Boolean = true,
        @Volatile
        var runBeforeCommit: Boolean = false,
        @Volatile
        var runBeforePush: Boolean = false,
        @Volatile
        var stopAfterFirstFailure: Boolean = false,
    )

    @Volatile
    private var state = State()

    var baseBranch: String
        get() = state.baseBranch
        set(value) { state.baseBranch = value }

    var checkConsumers: Boolean
        get() = state.checkConsumers
        set(value) { state.checkConsumers = value }

    var animateWhileRunning: Boolean
        get() = state.animateWhileRunning
        set(value) { state.animateWhileRunning = value }

    var runBeforeCommit: Boolean
        get() = state.runBeforeCommit
        set(value) { state.runBeforeCommit = value }

    var runBeforePush: Boolean
        get() = state.runBeforePush
        set(value) { state.runBeforePush = value }

    var stopAfterFirstFailure: Boolean
        get() = state.stopAfterFirstFailure
        set(value) { state.stopAfterFirstFailure = value }

    override fun getState(): State = state

    override fun loadState(newState: State) {
        state = newState
    }

    companion object {
        fun getInstance(): AffectedSettings =
            ApplicationManager.getApplication().getService(AffectedSettings::class.java)
    }
}
