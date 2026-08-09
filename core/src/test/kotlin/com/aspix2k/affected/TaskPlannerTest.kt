package com.aspix2k.affected

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskPlannerTest {

    private fun jvm(
        path: String,
        root: String = "/repo",
        tests: Boolean = true,
        executionRoot: String = root,
        executionId: String = path,
    ) = ModuleInfo(
        path,
        "GRADLE",
        root,
        testTask = "test",
        compileTask = "compileTestKotlin",
        hasTests = tests,
        executionRoot = executionRoot,
        executionId = executionId,
    )

    private fun android(path: String, root: String = "/repo", tests: Boolean = true) =
        ModuleInfo(
            path,
            "GRADLE",
            root,
            testTask = "testDebugUnitTest",
            compileTask = "compileDebugUnitTestKotlin",
            hasTests = tests,
        )

    @Test
    fun `empty input yields an empty plan`() {
        val plan = TaskPlanner.plan(emptyList(), emptyList())
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.tested)
        assertEquals(0, plan.compiled)
    }

    @Test
    fun `a JVM module gets the test task`() {
        val plan = TaskPlanner.plan(listOf(jvm(":core")), emptyList())
        assertEquals(listOf(":core:test"), plan.groups.single { it.root == "/repo" }.tasks)
    }

    @Test
    fun `an Android module gets a variant task`() {
        val plan = TaskPlanner.plan(listOf(android(":app")), emptyList())
        assertEquals(listOf(":app:testDebugUnitTest"), plan.groups.single { it.root == "/repo" }.tasks)
    }

    @Test
    fun `a module without tests is skipped`() {
        val plan = TaskPlanner.plan(listOf(jvm(":no-tests", tests = false)), emptyList())
        assertTrue(plan.isEmpty, "there is no reason to run missing tests")
        assertEquals(0, plan.tested)
    }

    @Test
    fun `a consumer gets a compilation task`() {
        val plan = TaskPlanner.plan(listOf(jvm(":core")), listOf(android(":app")))
        assertEquals(
            listOf(":core:test", ":app:compileDebugUnitTestKotlin"),
            plan.groups.single { it.root == "/repo" }.tasks,
        )
        assertEquals(1, plan.compiled)
    }

    @Test
    fun `a consumer without tests is still compiled`() {
        val plan = TaskPlanner.plan(emptyList(), listOf(jvm(":consumer", tests = false)))
        assertEquals(listOf(":consumer:compileTestKotlin"), plan.groups.single { it.root == "/repo" }.tasks)
    }

    @Test
    fun `a changed consumer module is not checked twice`() {
        val core = jvm(":core")
        val plan = TaskPlanner.plan(listOf(core), listOf(core))
        assertEquals(listOf(":core:test"), plan.groups.single { it.root == "/repo" }.tasks)
        assertEquals(0, plan.compiled, "tests already cover this module compilation")
    }

    @Test
    fun `tasks are grouped by their builds`() {
        val plan = TaskPlanner.plan(
            listOf(jvm(":core", root = "/repo/features")),
            listOf(android(":app", root = "/repo/app")),
        )
        assertEquals(listOf(":core:test"), plan.groups.single { it.root == "/repo/features" }.tasks)
        assertEquals(listOf(":app:compileDebugUnitTestKotlin"), plan.groups.single { it.root == "/repo/app" }.tasks)
    }

    @Test
    fun `included builds sharing an execution tree use one command`() {
        val plan = TaskPlanner.plan(
            changed = listOf(
                jvm(
                    ":ui-shell",
                    root = "/repo/store-build",
                    executionRoot = "/repo",
                    executionId = ":store-build:ui-shell",
                ),
                jvm(
                    ":shared-ui",
                    root = "/repo/platform",
                    executionRoot = "/repo",
                    executionId = ":platform:shared-ui",
                ),
            ),
            consumers = emptyList(),
        )

        assertEquals(1, plan.groups.size)
        assertEquals("/repo", plan.groups.single().root)
        assertEquals(
            listOf(
                ":store-build:ui-shell:test",
                ":platform:shared-ui:test",
            ),
            plan.groups.single().tasks,
        )
    }

    @Test
    fun `independent execution trees keep separate commands`() {
        val plan = TaskPlanner.plan(
            changed = listOf(
                jvm(":core", root = "/repo/one", executionRoot = "/repo/one"),
                jvm(":core", root = "/repo/two", executionRoot = "/repo/two"),
            ),
            consumers = emptyList(),
        )

        assertEquals(2, plan.groups.size)
    }

    @Test
    fun `a named task uses shared execution coordinates`() {
        val groups = TaskPlanner.groups(
            modules = listOf(
                jvm(
                    ":ui-shell",
                    root = "/repo/store-build",
                    executionRoot = "/repo",
                    executionId = ":store-build:ui-shell",
                ),
                jvm(
                    ":shared-ui",
                    root = "/repo/platform",
                    executionRoot = "/repo",
                    executionId = ":platform:shared-ui",
                ),
            ),
            task = "detekt",
        )

        assertEquals(1, groups.size)
        assertEquals(
            listOf(
                ":store-build:ui-shell:detekt",
                ":platform:shared-ui:detekt",
            ),
            groups.single().tasks,
        )
    }

    @Test
    fun `duplicates are collapsed`() {
        val core = jvm(":core")
        val plan = TaskPlanner.plan(listOf(core, core, core), emptyList())
        assertEquals(listOf(":core:test"), plan.groups.single { it.root == "/repo" }.tasks)
        assertEquals(1, plan.tested)
    }

    @Test
    fun `conflicting execution coordinates fall back to one owning module`() {
        val plan = TaskPlanner.plan(
            changed = listOf(
                jvm(
                    ":core",
                    root = "/repo/features",
                    executionRoot = "/repo",
                    executionId = ":features:core",
                ),
                jvm(
                    ":core",
                    root = "/repo/features",
                    executionRoot = "/repo/other",
                    executionId = ":renamed:core",
                ),
            ),
            consumers = emptyList(),
        )

        assertEquals(1, plan.tested)
        assertEquals("/repo/features", plan.groups.single().root)
        assertEquals(listOf(":core:test"), plan.groups.single().tasks)
    }

    @Test
    fun `named tasks deduplicate by owning module`() {
        val groups = TaskPlanner.groups(
            modules = listOf(
                jvm(
                    ":core",
                    root = "/repo/features",
                    executionRoot = "/repo",
                    executionId = ":features:core",
                ),
                jvm(
                    ":core",
                    root = "/repo/features",
                    executionRoot = "/repo/other",
                    executionId = ":renamed:core",
                ),
            ),
            task = "detekt",
        )

        assertEquals(1, groups.size)
        assertEquals("/repo/features", groups.single().root)
        assertEquals(listOf(":core:detekt"), groups.single().tasks)
    }

    @Test
    fun `duplicate consumers are collapsed`() {
        val app = android(":app")
        val plan = TaskPlanner.plan(listOf(jvm(":core")), listOf(app, app, app))
        assertEquals(1, plan.compiled)
        assertEquals(
            listOf(":core:test", ":app:compileDebugUnitTestKotlin"),
            plan.groups.single { it.root == "/repo" }.tasks,
        )
    }

    @Test
    fun `a consumer sharing a tested path is not duplicated`() {
        val core = jvm(":core")
        val sameCore = jvm(":core")
        val plan = TaskPlanner.plan(listOf(core), listOf(sameCore))
        assertEquals(listOf(":core:test"), plan.groups.single { it.root == "/repo" }.tasks)
        assertEquals(0, plan.compiled)
    }

    @Test
    fun `a changed module without tests is still compiled as a consumer`() {
        val noTests = jvm(":no-tests", tests = false)
        val plan = TaskPlanner.plan(listOf(noTests), listOf(jvm(":other")))
        assertEquals(0, plan.tested)
        assertTrue(plan.groups.single { it.root == "/repo" }.tasks.contains(":other:compileTestKotlin"))
    }

    @Test
    fun `consumers alone can produce a plan`() {
        val plan = TaskPlanner.plan(emptyList(), listOf(jvm(":app")))
        assertEquals(0, plan.tested)
        assertEquals(1, plan.compiled)
    }

    @Test
    fun `the plan counts both groups`() {
        val plan = TaskPlanner.plan(listOf(jvm(":core")), listOf(jvm(":app")))
        assertEquals(1, plan.tested)
        assertEquals(1, plan.compiled)
    }

    @Test
    fun `the same module in different builds is distinct`() {
        val plan = TaskPlanner.plan(
            listOf(jvm(":ui-shell", root = "/repo/alpha")),
            listOf(jvm(":ui-shell", root = "/repo/beta")),
        )
        assertEquals(2, plan.groups.size, "the same name in different builds must not collapse")
    }

    @Test
    fun `modules from different build systems do not share a command`() {
        val gradle = ModuleInfo(":core", "GRADLE", "/repo", "test", "compileTestKotlin", hasTests = true)
        val maven = ModuleInfo("core", "MAVEN", "/repo", "test", "test-compile", hasTests = true)

        val plan = TaskPlanner.plan(listOf(gradle, maven), emptyList())

        assertEquals(2, plan.groups.size, "each system has its own command even with a shared root")
        assertEquals(listOf(":core:test"), plan.groups.single { it.systemId == "GRADLE" }.tasks)
        assertEquals(listOf("core:test"), plan.groups.single { it.systemId == "MAVEN" }.tasks)
    }
}
