package com.aspix2k.affected

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RegisteredActionsTest : BasePlatformTestCase() {

    fun testEveryRegisteredActionIsCreatedAndUpdatesWithoutFailing() {
        val manager = ActionManager.getInstance()
        val ids = manager.getActionIdList(PREFIX)
        assertTrue(ids.toString(), ids.size >= MINIMUM_ACTIONS)
        val context = SimpleDataContext.getProjectContext(project)

        ids.forEach { id ->
            val action = requireNotNull(manager.getAction(id)) { id }
            val event = AnActionEvent.createEvent(
                context,
                action.templatePresentation.clone(),
                ActionPlaces.UNKNOWN,
                ActionUiKind.NONE,
                null,
            )
            action.update(event)
            assertNotNull(id, event.getData(CommonDataKeys.PROJECT))
        }
    }

    private companion object {
        const val PREFIX = "com.aspix2k.affected."
        const val MINIMUM_ACTIONS = 15
    }
}
