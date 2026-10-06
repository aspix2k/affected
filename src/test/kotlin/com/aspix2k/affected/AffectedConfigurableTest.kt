package com.aspix2k.affected

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil

class AffectedConfigurableTest : BasePlatformTestCase() {

    fun testThePageShowsAndAppliesTheSettings() {
        val settings = AffectedSettings.getInstance()
        val previous = settings.state.copy()
        val branch = project.service<ProjectBaseBranch>()
        val configurable = AffectedConfigurable(project)
        try {
            val panel = requireNotNull(configurable.createComponent())
            configurable.reset()
            assertFalse(configurable.isModified)

            val field = UIUtil.findComponentsOfType(panel, JBTextField::class.java).single()
            val dependents = UIUtil.findComponentsOfType(panel, JBCheckBox::class.java)
                .single { it.text == AffectedBundle.message("action.dependents.text") }
            field.text = "release"
            dependents.isSelected = !settings.testDependents

            assertTrue(configurable.isModified)
            configurable.apply()
            assertEquals("release", branch.configured)
            assertEquals(dependents.isSelected, settings.testDependents)
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
            branch.configure("")
            settings.loadState(previous)
        }
    }
}
