package com.aspix2k.affected

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

class AffectedConfigurable(private val project: Project) :
    BoundConfigurable(AffectedBundle.message("settings.display.name")) {

    private val settings = AffectedSettings.getInstance()
    private val baseBranch = project.service<ProjectBaseBranch>()
    private var branch = baseBranch.configured.orEmpty()

    override fun createPanel(): DialogPanel = panel {
        row(AffectedBundle.message("dialog.base.branch.title")) {
            textField()
                .columns(COLUMNS_MEDIUM)
                .bindText(::branch)
                .comment(AffectedBundle.message("dialog.base.branch.message"))
                .validationOnApply { field ->
                    error(AffectedBundle.message("settings.base.branch.invalid"))
                        .takeIf { AffectedMcpInputs.validateBaseBranch(field.text).error }
                }
        }
        separator()
        row { checkBox(AffectedBundle.message("action.consumers.text")).bindSelected(settings::checkConsumers) }
        row { checkBox(AffectedBundle.message("action.dependents.text")).bindSelected(settings::testDependents) }
        row {
            checkBox(AffectedBundle.message("action.failure.stop.text")).bindSelected(settings::stopAfterFirstFailure)
        }
        separator()
        row { checkBox(AffectedBundle.message("action.before.commit.text")).bindSelected(settings::runBeforeCommit) }
        row { checkBox(AffectedBundle.message("action.before.push.text")).bindSelected(settings::runBeforePush) }
        row {
            checkBox(AffectedBundle.message("action.guards.dependents.text"))
                .bindSelected(settings::guardsTestDependents)
        }
        separator()
        row { checkBox(AffectedBundle.message("action.animation.text")).bindSelected(settings::animateWhileRunning) }
    }

    override fun reset() {
        branch = baseBranch.configured.orEmpty()
        super.reset()
    }

    override fun apply() {
        super.apply()
        baseBranch.configure(branch)
        invalidateOpenProjects()
    }
}
