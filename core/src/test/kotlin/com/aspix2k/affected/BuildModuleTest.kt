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
    fun `ключ различает одинаковые имена в разных сборках`() {
        val one = module(":app", root = "/repo")
        val other = module(":app", root = "/repo/included")

        assertNotEquals(one.key, other.key, "модули из разных сборок обязаны различаться")
    }

    @Test
    fun `ключ совпадает у одного и того же модуля`() {
        assertEquals(module(":core").key, module(":core").key)
    }

    @Test
    fun `зависимость записывается ключом, а не именем`() {
        val core = module(":core")
        val app = module(":app", dependencies = setOf(core.key))

        assertEquals(setOf("/repo|:core"), app.dependencies)
    }
}
