package com.aspix2k.affected

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskPlannerTest {

    private fun jvm(path: String, root: String = "/repo", tests: Boolean = true) =
        ModuleInfo(path, root, isAndroid = false, hasTests = tests)

    private fun android(path: String, root: String = "/repo", tests: Boolean = true) =
        ModuleInfo(path, root, isAndroid = true, hasTests = tests)

    @Test
    fun `пустой ввод даёт пустой план`() {
        val plan = TaskPlanner.plan(emptyList(), emptyList())
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.tested)
        assertEquals(0, plan.compiled)
    }

    @Test
    fun `jvm модуль получает задачу test`() {
        val plan = TaskPlanner.plan(listOf(jvm(":core")), emptyList())
        assertEquals(listOf(":core:test"), plan.tasksByRoot.getValue("/repo"))
    }

    @Test
    fun `android модуль получает задачу с вариантом`() {
        val plan = TaskPlanner.plan(listOf(android(":app")), emptyList())
        assertEquals(listOf(":app:testDebugUnitTest"), plan.tasksByRoot.getValue("/repo"))
    }

    @Test
    fun `модуль без тестов пропускается`() {
        val plan = TaskPlanner.plan(listOf(jvm(":no-tests", tests = false)), emptyList())
        assertTrue(plan.isEmpty, "запускать тесты там, где их нет, незачем")
        assertEquals(0, plan.tested)
    }

    @Test
    fun `потребитель получает задачу компиляции`() {
        val plan = TaskPlanner.plan(listOf(jvm(":core")), listOf(android(":app")))
        assertEquals(listOf(":core:test", ":app:compileDebugUnitTestKotlin"), plan.tasksByRoot.getValue("/repo"))
        assertEquals(1, plan.compiled)
    }

    @Test
    fun `потребитель без тестов всё равно компилируется`() {
        val plan = TaskPlanner.plan(emptyList(), listOf(jvm(":consumer", tests = false)))
        assertEquals(listOf(":consumer:compileTestKotlin"), plan.tasksByRoot.getValue("/repo"))
    }

    @Test
    fun `модуль не проверяется дважды если он и изменён и потребитель`() {
        val core = jvm(":core")
        val plan = TaskPlanner.plan(listOf(core), listOf(core))
        assertEquals(listOf(":core:test"), plan.tasksByRoot.getValue("/repo"))
        assertEquals(0, plan.compiled, "тесты уже покрывают компиляцию этого модуля")
    }

    @Test
    fun `задачи группируются по своим сборкам`() {
        val plan = TaskPlanner.plan(
            listOf(jvm(":core", root = "/repo/features")),
            listOf(android(":app", root = "/repo/app")),
        )
        assertEquals(listOf(":core:test"), plan.tasksByRoot.getValue("/repo/features"))
        assertEquals(listOf(":app:compileDebugUnitTestKotlin"), plan.tasksByRoot.getValue("/repo/app"))
    }

    @Test
    fun `дубликаты схлопываются`() {
        val core = jvm(":core")
        val plan = TaskPlanner.plan(listOf(core, core, core), emptyList())
        assertEquals(listOf(":core:test"), plan.tasksByRoot.getValue("/repo"))
        assertEquals(1, plan.tested)
    }

    @Test
    fun `дубликаты среди потребителей схлопываются`() {
        val app = android(":app")
        val plan = TaskPlanner.plan(listOf(jvm(":core")), listOf(app, app, app))
        assertEquals(1, plan.compiled)
        assertEquals(listOf(":core:test", ":app:compileDebugUnitTestKotlin"), plan.tasksByRoot.getValue("/repo"))
    }

    @Test
    fun `потребитель совпадающий по пути с тестируемым не дублируется`() {
        val core = jvm(":core")
        val sameCore = jvm(":core")
        val plan = TaskPlanner.plan(listOf(core), listOf(sameCore))
        assertEquals(listOf(":core:test"), plan.tasksByRoot.getValue("/repo"))
        assertEquals(0, plan.compiled)
    }

    @Test
    fun `изменённый модуль без тестов всё равно компилируется как потребитель`() {
        val noTests = jvm(":no-tests", tests = false)
        val plan = TaskPlanner.plan(listOf(noTests), listOf(jvm(":other")))
        assertEquals(0, plan.tested)
        assertTrue(plan.tasksByRoot.getValue("/repo").contains(":other:compileTestKotlin"))
    }

    @Test
    fun `только потребители без изменённых модулей дают план`() {
        val plan = TaskPlanner.plan(emptyList(), listOf(jvm(":app")))
        assertEquals(0, plan.tested)
        assertEquals(1, plan.compiled)
    }

    @Test
    fun `план считает обе группы`() {
        val plan = TaskPlanner.plan(listOf(jvm(":core")), listOf(jvm(":app")))
        assertEquals(1, plan.tested)
        assertEquals(1, plan.compiled)
    }

    @Test
    fun `один и тот же модуль в разных сборках это разные модули`() {
        val plan = TaskPlanner.plan(
            listOf(jvm(":app-integration", root = "/repo/online")),
            listOf(jvm(":app-integration", root = "/repo/market")),
        )
        assertEquals(2, plan.tasksByRoot.size, "одинаковое имя в разных сборках не должно схлопываться")
    }
}
