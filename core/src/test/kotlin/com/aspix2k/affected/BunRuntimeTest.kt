package com.aspix2k.affected

import com.aspix2k.affected.build.bunDeclared
import com.aspix2k.affected.build.bunManager
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BunRuntimeTest {

    @Test
    fun `a bun lockfile is bun-only`() {
        val root = project()
        File(root, "package.json").writeText("""{ "name": "app" }""")
        File(root, "bun.lock").writeText("")

        assertTrue(bunDeclared(root))
        assertEquals("bun", bunManager(root))
    }

    @Test
    fun `packageManager bun without a lockfile is bun-only`() {
        val root = project()
        File(root, "package.json").writeText("""{ "name": "app", "packageManager": "bun@1.2.21" }""")

        assertEquals("bun", bunManager(root))
    }

    @Test
    fun `bun plus yarn stays undecided`() {
        val root = project()
        File(root, "package.json").writeText("""{ "name": "app" }""")
        File(root, "bun.lock").writeText("")
        File(root, "yarn.lock").writeText("")

        assertTrue(bunDeclared(root))
        assertNull(bunManager(root))
    }

    @Test
    fun `npm lockfile alone is not bun`() {
        val root = project()
        File(root, "package.json").writeText("""{ "name": "app" }""")
        File(root, "package-lock.json").writeText("{}")

        assertFalse(bunDeclared(root))
        assertNull(bunManager(root))
    }

    private fun project(): File = createTempDirectory("bun-runtime").toFile()
}
