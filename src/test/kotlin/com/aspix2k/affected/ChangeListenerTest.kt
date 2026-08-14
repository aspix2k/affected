package com.aspix2k.affected

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChangeListenerTest {

    private val extensions = setOf("kt", "rs", "go", "py", "ts", "cs", "php", "rb", "cpp")

    @Test
    fun `source changes from every supported language are relevant`() {
        listOf("Main.kt", "lib.rs", "main.go", "app.py", "index.ts", "App.cs", "index.php", "app.rb", "main.cpp")
            .forEach { assertTrue(isRelevantPath("/project/src/$it", extensions), it) }
    }

    @Test
    fun `extensionless build manifests are relevant by name`() {
        assertTrue(isRelevantPath("/project/Gemfile", extensions, setOf("Gemfile")))
        assertFalse(isRelevantPath("/project/README", extensions, setOf("Gemfile")))
    }

    @Test
    fun `generated and VCS files are ignored`() {
        assertFalse(isRelevantPath("/project/build/generated/Main.kt", extensions))
        assertFalse(isRelevantPath("/project/.gradle/cache/lib.rs", extensions))
        assertFalse(isRelevantPath("/project/.git/worktrees/main.go", extensions))
        assertFalse(isRelevantPath("/project/node_modules/package/index.ts", extensions))
        assertFalse(isRelevantPath("/project/target/debug/generated.rs", extensions))
    }

    @Test
    fun `a JetBrains Client prefix is a proven remote frontend`() {
        assertTrue(remoteFrontendProven(platformPrefix = "JetBrainsClient", rdctClient = null))
    }

    @Test
    fun `an rdct client flag is a proven remote frontend`() {
        assertTrue(remoteFrontendProven(platformPrefix = "Idea", rdctClient = "true"))
    }

    @Test
    fun `a local IDE is not a remote frontend`() {
        assertFalse(remoteFrontendProven(platformPrefix = "Idea", rdctClient = null))
        assertFalse(remoteFrontendProven(platformPrefix = null, rdctClient = "false"))
    }

    @Test
    fun `a proven remote frontend does not refresh from VFS events`() {
        assertFalse(
            shouldRefreshFromVfs(
                frontend = true,
                paths = listOf("/project/src/Main.kt"),
                extensions = extensions,
                names = emptySet(),
            ),
        )
    }

    @Test
    fun `a local IDE still refreshes from a source change`() {
        assertTrue(
            shouldRefreshFromVfs(
                frontend = false,
                paths = listOf("/project/src/Main.kt"),
                extensions = extensions,
                names = emptySet(),
            ),
        )
    }
}
