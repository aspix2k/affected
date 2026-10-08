package com.aspix2k.affected

import com.intellij.dvcs.push.PrePushHandler
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.lang.reflect.Proxy
import javax.swing.JCheckBox

class CommitAndPushGuardsTest : BasePlatformTestCase() {

    private val settings get() = AffectedSettings.getInstance()
    private var runBeforeCommit = false
    private var runBeforePush = false

    override fun setUp() {
        super.setUp()
        runBeforeCommit = settings.runBeforeCommit
        runBeforePush = settings.runBeforePush
    }

    override fun tearDown() {
        try {
            settings.runBeforeCommit = runBeforeCommit
            settings.runBeforePush = runBeforePush
        } finally {
            super.tearDown()
        }
    }

    fun testTheCommitCheckboxShowsAndStoresTheSetting() {
        val handler = AffectedCheckinHandlerFactory().createHandler(commitPanel(), CommitContext())
        val configuration = requireNotNull(handler.beforeCheckinConfigurationPanel)
        val checkBox = UIUtil.findComponentsOfType(configuration.component, JCheckBox::class.java).single()

        settings.runBeforeCommit = true
        configuration.restoreState()
        assertTrue(checkBox.isSelected)
        assertEquals(AffectedBundle.message("checkin.run.text"), checkBox.text)

        checkBox.isSelected = false
        configuration.saveState()
        assertFalse(settings.runBeforeCommit)
    }

    fun testASwitchedOffGuardLetsTheCommitAndThePushThrough() {
        settings.runBeforeCommit = false
        settings.runBeforePush = false
        val handler = AffectedCheckinHandlerFactory().createHandler(commitPanel(), CommitContext())
        val push = AffectedPrePushHandler()

        assertEquals(CheckinHandler.ReturnResult.COMMIT, handler.beforeCheckin())
        assertEquals(PrePushHandler.Result.OK, push.handle(project, emptyList(), EmptyProgressIndicator()))
        assertEquals(AffectedBundle.message("push.handler.name"), push.presentableName)
    }

    private fun commitPanel(): CheckinProjectPanel = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(CheckinProjectPanel::class.java),
    ) { _, method, _ ->
        if (method.name == "getProject") project else error("${method.name} is not expected")
    } as CheckinProjectPanel
}
