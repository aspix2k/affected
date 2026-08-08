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
    fun `a missing directory does not crash analysis`() {
        val missing = File("/definitely/does/not/exist/anywhere")
        val changes = ChangeAnalyzer(missing, "main").collect()
        assertTrue(changes.files.isEmpty())
        assertTrue(changes.apiTouched.isEmpty())
    }

    @Test
    fun `an empty branch does not crash analysis`() {
        val dir = createTempDirectory("crash-empty-branch").toFile()
        assertTrue(ChangeAnalyzer(dir, "").collect().files.isEmpty())
    }

    @Test
    fun `an unreadable directory does not crash analysis`() {
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
    fun `a binary file with a source extension does not crash analysis`() {
        val dir = createTempDirectory("crash-binary").toFile()
        run(dir, "git", "init", "-q", "-b", "main")
        run(dir, "git", "config", "user.email", "t@e.com")
        run(dir, "git", "config", "user.name", "t")
        File(dir, "settings.gradle.kts").writeText("")
        File(dir, "lib").mkdirs()
        File(dir, "lib/build.gradle.kts").writeText("")
        Files.write(File(dir, "lib/Broken.kt").toPath(), byteArrayOf(0, -1, -2, 65, 0, 66))

        val changes = ChangeAnalyzer(dir, "main").collect()
        assertTrue(changes.files.any { it.name == "Broken.kt" }, "the file is still listed")
    }

    @Test
    fun `a very long diff line does not crash parsing`() {
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
    fun `a plan with empty paths does not crash the planner`() {
        val plan = TaskPlanner.plan(
            listOf(ModuleInfo("", "GRADLE", "", testTask = "test", compileTask = "compileTestKotlin", hasTests = true)),
            emptyList(),
        )
        assertEquals(1, plan.tested)
    }

    @Test
    fun `a large module count does not crash the planner`() {
        val modules = (1..5_000).map {
            ModuleInfo(
                ":m$it",
                "GRADLE",
                "/repo",
                testTask = if (it % 2 == 0) "testDebugUnitTest" else "test",
                compileTask = "compileTestKotlin",
                hasTests = true,
            )
        }
        val plan = TaskPlanner.plan(modules, modules.take(100))
        assertEquals(5_000, plan.tested)
        assertEquals(0, plan.compiled, "consumers are already covered by tests")
    }

    @Test
    fun `duplicate modules do not multiply tasks`() {
        val one =
            ModuleInfo(
                ":core",
                "GRADLE",
                "/repo",
                testTask = "test",
                compileTask = "compileTestKotlin",
                hasTests = true,
            )
        val plan = TaskPlanner.plan(List(1_000) { one }, List(1_000) { one })
        assertEquals(listOf(":core:test"), plan.groups.single { it.root == "/repo" }.tasks)
    }

    private fun run(dir: File, vararg args: String) {
        ProcessBuilder(*args).directory(dir).redirectErrorStream(true).start().waitFor()
    }
}
