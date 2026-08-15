package com.aspix2k.affected

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.testFramework.LightVirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `an all-file project refreshes from an unknown in-root resource`() {
        assertTrue(
            shouldRefreshAllFilesForProject(
                frontend = false,
                projectRoot = "/project",
                paths = listOf("/project/NAMESPACE", "/project/vignettes/guide.Rmd"),
            ),
        )
    }

    @Test
    fun `an all-file project ignores foreign docs and generated output`() {
        assertFalse(
            shouldRefreshAllFilesForProject(
                frontend = false,
                projectRoot = "/project",
                paths = listOf(
                    "/other/NAMESPACE",
                    "/project/README.md",
                    "/project/build/generated/data.csv",
                ),
            ),
        )
    }

    @Test
    fun `an all-file project refreshes for a snapshot named like project documentation`() {
        assertTrue(
            shouldRefreshAllFilesForProject(
                frontend = false,
                projectRoot = "/project",
                paths = listOf("/project/tests/testthat/_snaps/readme.md"),
            ),
        )
    }

    @Test
    fun `a moved resource reports both its old and new paths`() {
        val file = TestVirtualFile("input.csv", TestVirtualFile("project", directory = true))
        val target = TestVirtualFile("outside", directory = true)
        val event = VFileMoveEvent(this, file, target)

        assertEquals(listOf(event.oldPath, event.newPath), affectedVfsPaths(listOf(event)))
    }

    @Test
    fun `a renamed resource reports both its old and new paths`() {
        val file = TestVirtualFile("input.csv", TestVirtualFile("project", directory = true))
        val event = VFilePropertyChangeEvent(
            this,
            file,
            VirtualFile.PROP_NAME,
            "input.csv",
            "renamed.csv",
        )

        assertEquals(listOf(event.oldPath, event.newPath), affectedVfsPaths(listOf(event)))
    }

    @Test
    fun `Windows separators still ignore generated and VCS files`() {
        assertFalse(isRelevantPath("C:\\project\\.git\\worktrees\\main.go", extensions))
        assertFalse(isRelevantPath("C:\\project\\.gradle\\cache\\lib.rs", extensions))
        assertFalse(isRelevantPath("C:\\project\\build\\generated\\Main.kt", extensions))
    }

    @Test
    fun `a path with spaces stays relevant`() {
        assertTrue(isRelevantPath("/Users/me/my project/src/Main.kt", extensions))
        assertTrue(isRelevantPath("C:\\Users\\me\\my project\\src\\Main.kt", extensions))
    }

    @Test
    fun `a non-ASCII path stays relevant`() {
        assertTrue(isRelevantPath("/Users/я/проект/src/Главный.kt", extensions))
        assertTrue(isRelevantPath("C:\\Users\\я\\проект\\src\\Главный.kt", extensions))
    }

    @Test
    fun `a proven remote frontend does not analyze on startup`() {
        assertFalse(shouldRefreshOnStartup(frontend = true))
    }

    @Test
    fun `a local IDE still analyzes on startup`() {
        assertTrue(shouldRefreshOnStartup(frontend = false))
    }

    private class TestVirtualFile(
        name: String,
        private val parentFile: VirtualFile? = null,
        private val directory: Boolean = false,
    ) : LightVirtualFile(name) {
        override fun getParent(): VirtualFile? = parentFile

        override fun isDirectory(): Boolean = directory

        override fun getPath(): String = parentFile?.let { "${it.path}/$name" } ?: "/$name"
    }
}
