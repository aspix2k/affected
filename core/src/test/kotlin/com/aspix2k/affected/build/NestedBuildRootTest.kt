package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NestedBuildRootTest {

    @Test
    fun `a marker on the project base wins`() {
        val base = createTempDirectory("nested-base").toFile()
        File(base, "CMakeLists.txt").writeText("")
        File(base, "cpp").mkdirs()
        File(base, "cpp/CMakeLists.txt").writeText("")

        assertEquals(base.canonicalFile, nestedBuildRoot(base, ::hasCMakeLists)?.canonicalFile)
    }

    @Test
    fun `a single first-level nested marker is the root`() {
        val base = createTempDirectory("nested-one").toFile()
        File(base, "cpp").mkdirs()
        File(base, "cpp/CMakeLists.txt").writeText("")

        assertEquals(File(base, "cpp").canonicalFile, nestedBuildRoot(base, ::hasCMakeLists)?.canonicalFile)
    }

    @Test
    fun `several first-level nested markers stay off`() {
        val base = createTempDirectory("nested-many").toFile()
        File(base, "cpp").mkdirs()
        File(base, "cpp/CMakeLists.txt").writeText("")
        File(base, "extra").mkdirs()
        File(base, "extra/CMakeLists.txt").writeText("")

        assertNull(nestedBuildRoot(base, ::hasCMakeLists))
    }

    @Test
    fun `a deeper nested marker stays off`() {
        val base = createTempDirectory("nested-deep").toFile()
        File(base, "src/cpp").mkdirs()
        File(base, "src/cpp/CMakeLists.txt").writeText("")

        assertNull(nestedBuildRoot(base, ::hasCMakeLists))
    }

    @Test
    fun `a second-level nested marker is a multi-root root`() {
        val base = createTempDirectory("nested-second").toFile()
        markers(base, "src/cpp")

        assertEquals(listOf(File(base, "src/cpp")), nestedBuildRoots(base, ::hasCMakeLists))
    }

    @Test
    fun `a marker deeper than three levels stays off`() {
        val base = createTempDirectory("nested-too-deep").toFile()
        markers(base, "a/b/c/d")

        assertEquals(emptyList(), nestedBuildRoots(base, ::hasCMakeLists))
    }

    @Test
    fun `a marker on the project base is the only root`() {
        val base = createTempDirectory("nested-roots-base").toFile()
        markers(base, ".", "apps/web")

        assertEquals(listOf(base), nestedBuildRoots(base, ::hasCMakeLists))
    }

    @Test
    fun `sibling markers two levels deep are all roots`() {
        val base = createTempDirectory("nested-siblings").toFile()
        markers(base, "apps/web", "apps/admin")

        assertEquals(
            listOf(File(base, "apps/admin"), File(base, "apps/web")),
            nestedBuildRoots(base, ::hasCMakeLists),
        )
    }

    @Test
    fun `a marker three levels deep is a root`() {
        val base = createTempDirectory("nested-three").toFile()
        markers(base, "services/api/core")

        assertEquals(listOf(File(base, "services/api/core")), nestedBuildRoots(base, ::hasCMakeLists))
    }

    @Test
    fun `members inside a found root are not separate roots`() {
        val base = createTempDirectory("nested-member").toFile()
        markers(base, "apps/web", "apps/web/packages/ui", "apps/admin")

        assertEquals(
            listOf(File(base, "apps/admin"), File(base, "apps/web")),
            nestedBuildRoots(base, ::hasCMakeLists),
        )
    }

    @Test
    fun `skipped directories are not searched`() {
        val base = createTempDirectory("nested-skip").toFile()
        markers(base, "apps/web", "node_modules/pkg", "apps/build/out", "target/x")

        assertEquals(listOf(File(base, "apps/web")), nestedBuildRoots(base, ::hasCMakeLists))
    }

    @Test
    fun `a symlinked directory is not searched`() {
        val base = createTempDirectory("nested-roots-link").toFile()
        val external = createTempDirectory("nested-roots-external").toFile()
        markers(external, "web")
        markers(base, "apps/api")
        assumeTrue(runCatching { Files.createSymbolicLink(File(base, "linked").toPath(), external.toPath()) }.isSuccess)

        assertEquals(listOf(File(base, "apps/api")), nestedBuildRoots(base, ::hasCMakeLists))
    }

    @Test
    fun `the root cap is inclusive and one more root returns no roots`() {
        val base = createTempDirectory("nested-many-roots").toFile()
        markers(base, *(1..PerformanceBudgets.MAX_NESTED_ROOTS).map { "apps/p$it" }.toTypedArray())
        assertEquals(PerformanceBudgets.MAX_NESTED_ROOTS, nestedBuildRoots(base, ::hasCMakeLists).size)

        markers(base, "apps/extra")

        assertEquals(emptyList(), nestedBuildRoots(base, ::hasCMakeLists))
    }

    @Test
    fun `too many visited directories return no roots`() {
        val base = createTempDirectory("nested-many-dirs").toFile()
        markers(base, "apps/web")
        repeat(PerformanceBudgets.MAX_DIRECTORIES) { File(base, "empty/d$it").mkdirs() }

        assertEquals(emptyList(), nestedBuildRoots(base, ::hasCMakeLists))
    }

    private fun markers(base: File, vararg paths: String) = paths.forEach { path ->
        File(base, path).apply { mkdirs() }.let { File(it, "CMakeLists.txt").writeText("") }
    }

    @Test
    fun `an ignored first-level directory is not a root`() {
        val base = createTempDirectory("nested-ignored").toFile()
        File(base, "node_modules").mkdirs()
        File(base, "node_modules/CMakeLists.txt").writeText("")

        assertNull(nestedBuildRoot(base, ::hasCMakeLists))
    }

    @Test
    fun `a symlinked first-level marker directory is not a root`() {
        val base = createTempDirectory("nested-link-base").toFile()
        val external = createTempDirectory("nested-link-external").toFile()
        File(external, "CMakeLists.txt").writeText("")
        assumeTrue(runCatching { Files.createSymbolicLink(File(base, "cpp").toPath(), external.toPath()) }.isSuccess)

        assertNull(nestedBuildRoot(base, ::hasCMakeLists))
    }

    @Test
    fun `a dangling first-level directory symlink is not a root`() {
        val base = createTempDirectory("nested-dangling-base").toFile()
        val target = File(base, "missing")
        assumeTrue(runCatching { Files.createSymbolicLink(File(base, "cpp").toPath(), target.toPath()) }.isSuccess)

        assertNull(nestedBuildRoot(base, ::hasCMakeLists))
    }

    private fun hasCMakeLists(directory: File): Boolean =
        File(directory, "CMakeLists.txt").isRegularFileNoFollow()
}
