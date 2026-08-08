package com.aspix2k.affected

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CrashSafetyTest {

    @Test
    fun `несуществующий каталог не роняет анализ`() {
        val missing = File("/definitely/does/not/exist/anywhere")
        val changes = ChangeAnalyzer(missing, "main").collect()
        assertTrue(changes.files.isEmpty())
        assertTrue(changes.apiTouched.isEmpty())
    }

    @Test
    fun `пустая строка вместо ветки не роняет анализ`() {
        val dir = createTempDirectory("crash-empty-branch").toFile()
        assertTrue(ChangeAnalyzer(dir, "").collect().files.isEmpty())
    }

    @Test
    fun `каталог без прав на чтение не роняет анализ`() {
        val dir = createTempDirectory("crash-perms").toFile()
        val locked = File(dir, "locked").apply { mkdirs() }
        locked.setReadable(false, false)
        try {
            assertTrue(ChangeAnalyzer(dir, "main").collectPaths().isEmpty())
        } finally {
            locked.setReadable(true, false)
        }
    }

    @Test
    fun `бинарный файл с расширением исходника не роняет анализ`() {
        val dir = createTempDirectory("crash-binary").toFile()
        run(dir, "git", "init", "-q", "-b", "main")
        run(dir, "git", "config", "user.email", "t@e.com")
        run(dir, "git", "config", "user.name", "t")
        File(dir, "settings.gradle.kts").writeText("")
        File(dir, "lib").mkdirs()
        File(dir, "lib/build.gradle.kts").writeText("")
        Files.write(File(dir, "lib/Broken.kt").toPath(), byteArrayOf(0, -1, -2, 65, 0, 66))

        val changes = ChangeAnalyzer(dir, "main").collect()
        assertTrue(changes.files.any { it.name == "Broken.kt" }, "файл всё равно попадает в список")
    }

    @Test
    fun `очень длинная строка в дифе не роняет разбор`() {
        val dir = createTempDirectory("crash-longline").toFile()
        run(dir, "git", "init", "-q", "-b", "main")
        run(dir, "git", "config", "user.email", "t@e.com")
        run(dir, "git", "config", "user.name", "t")
        File(dir, "settings.gradle.kts").writeText("")
        File(dir, "lib").mkdirs()
        File(dir, "lib/build.gradle.kts").writeText("")
        File(dir, "lib/Huge.kt").writeText("val x = \"" + "a".repeat(500_000) + "\"\n")

        assertNotNull(ChangeAnalyzer(dir, "main").collect())
    }

    @Test
    fun `план с пустыми путями не роняет планировщик`() {
        val plan = TaskPlanner.plan(
            listOf(ModuleInfo("", "", isAndroid = false, hasTests = true)),
            emptyList(),
        )
        assertEquals(1, plan.tested)
    }

    @Test
    fun `огромное число модулей не роняет планировщик`() {
        val modules = (1..5_000).map { ModuleInfo(":m$it", "/repo", isAndroid = it % 2 == 0, hasTests = true) }
        val plan = TaskPlanner.plan(modules, modules.take(100))
        assertEquals(5_000, plan.tested)
        assertEquals(0, plan.compiled, "потребители уже покрыты тестами")
    }

    @Test
    fun `дубли одинаковых модулей не размножают задачи`() {
        val one = ModuleInfo(":core", "/repo", isAndroid = false, hasTests = true)
        val plan = TaskPlanner.plan(List(1_000) { one }, List(1_000) { one })
        assertEquals(listOf(":core:test"), plan.tasksByRoot.getValue("/repo"))
    }

    @Test
    fun `отрицательный и нулевой счётчик дают базовую иконку`() {
        assertEquals(AffectedIcons.Action, AffectedIcons.withCount(0))
        assertEquals(AffectedIcons.Action, AffectedIcons.withCount(-5))
    }

    @Test
    fun `большие значения счётчика схлопываются в один вариант`() {
        val huge = AffectedIcons.withCount(Int.MAX_VALUE)
        val slightly = AffectedIcons.withCount(100)
        assertEquals(huge, slightly, "всё, что больше порога, использует одну иконку")
    }

    @Test
    fun `счётчик кэшируется а не создаётся заново`() {
        assertEquals(AffectedIcons.withCount(7), AffectedIcons.withCount(7))
    }

    private fun run(dir: File, vararg args: String) {
        ProcessBuilder(*args).directory(dir).redirectErrorStream(true).start().waitFor()
    }
}
