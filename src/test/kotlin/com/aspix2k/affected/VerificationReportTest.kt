package com.aspix2k.affected

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking

class VerificationReportTest : BasePlatformTestCase() {

    fun testEveryVerdictAndEveryReasonHasItsLineInTheBaseCheckText() {
        val verdicts = listOf(
            BaseGroupVerdict("GRADLE", "app", listOf(":app:test"), BaseRun.Finished(passed = true)),
            BaseGroupVerdict("MAVEN", "<core>", listOf("g:core:test"), BaseRun.Finished(passed = false)),
        ) + BaseNotRun.entries.map { BaseGroupVerdict("GO", it.id, listOf("x:test"), BaseRun.Skipped(it)) }

        val text = baseCheckText(BaseCheckReport(verdicts, baseCommit = "0123456789abcdef", baseBranch = "main"))
        val lines = text.split("<br>")

        assertEquals(1 + verdicts.size, lines.size)
        assertTrue(lines.first(), "main 0123456789ab" in lines.first())
        assertEquals(AffectedBundle.message("notification.base.regression", "GRADLE app"), lines[1])
        assertEquals(AffectedBundle.message("notification.base.preexisting", "MAVEN &lt;core&gt;"), lines[2])
        BaseNotRun.entries.forEachIndexed { index, reason ->
            val explained = AffectedBundle.message("notification.base.reason.${reason.id}")
            assertFalse(reason.id, explained.startsWith("!"))
            assertTrue(lines[index + FIRST_UNKNOWN], explained in lines[index + FIRST_UNKNOWN])
        }
    }

    fun testTheBaseCheckResultIsReportedForEveryOutcome() {
        val shown = ArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    shown += notification
                }
            },
        )
        val regression = BaseGroupVerdict("GRADLE", "app", listOf(":app:test"), BaseRun.Finished(passed = true))
        val old = BaseGroupVerdict("GRADLE", "app", listOf(":app:test"), BaseRun.Finished(passed = false))

        reportBaseCheck(project, BaseCheckReport(blocker = BaseCheckBlocker.NOT_STARTED))
        reportBaseCheck(project, BaseCheckReport(blocker = BaseCheckBlocker.NO_FAILED_VERIFICATION))
        reportBaseCheck(project, BaseCheckReport(listOf(regression), baseCommit = "0123456789abcdef"))
        reportBaseCheck(project, BaseCheckReport(listOf(old), baseCommit = "0123456789abcdef"))

        assertEquals(REPORTS, shown.size)
        assertEquals(AffectedBundle.message("notification.base.nothing.text"), shown[1].content)
        assertEquals(NotificationType.WARNING, shown[2].type)
        assertEquals(NotificationType.INFORMATION, shown[3].type)
        assertTrue(shown.all { it.content.isNotBlank() && it.title.isNotBlank() })
    }

    fun testEveryBlockerIsReported() {
        val shown = ArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    shown += notification
                }
            },
        )
        val prepared = runBlocking { Verification.prepare(project) }

        Verification.Blocker.entries.forEach { reportBlocker(project, prepared, it) }
        reportBlocker(project, prepared, null)

        assertEquals(Verification.Blocker.entries.size, shown.size)
        assertEquals(Verification.Blocker.entries.size, shown.mapTo(HashSet()) { it.title }.size)
        assertTrue(shown.all { it.content.isNotBlank() && it.title.isNotBlank() })
    }

    private companion object {
        const val FIRST_UNKNOWN = 3
        const val REPORTS = 4
    }
}
