package com.aspix2k.affected

import com.aspix2k.affected.build.BuildModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BuildModuleTest {

    private fun module(id: String, root: String = "/repo", dependencies: Set<String> = emptySet()) =
        BuildModule(
            id = id,
            root = root,
            contentRoots = listOf("$root/${id.removePrefix(":")}"),
            testTask = "test",
            compileTask = "compileTestKotlin",
            hasTests = true,
            dependencies = dependencies,
        )

    @Test
    fun `the key distinguishes identical names in different builds`() {
        val one = module(":app", root = "/repo")
        val other = module(":app", root = "/repo/included")

        assertNotEquals(one.key, other.key, "modules from different builds must differ")
    }

    @Test
    fun `the key matches for the same module`() {
        assertEquals(module(":core").key, module(":core").key)
    }

    @Test
    fun `a dependency uses the key rather than the name`() {
        val core = module(":core")
        val app = module(":app", dependencies = setOf(core.key))

        assertEquals(setOf("/repo|:core"), app.dependencies)
    }
}
