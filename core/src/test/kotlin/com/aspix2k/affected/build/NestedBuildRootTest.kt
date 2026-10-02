package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

        assertEquals(listOf(File(base, "src/cpp")), nestedBuildRoots(base, CMAKE_LISTS, ::hasCMakeLists))
    }

    @Test
    fun `a marker deeper than three levels stays off`() {
        val base = createTempDirectory("nested-too-deep").toFile()
        markers(base, "a/b/c/d")

        assertEquals(emptyList(), nestedBuildRoots(base, CMAKE_LISTS, ::hasCMakeLists))
    }

    @Test
    fun `a marker on the project base is the only root`() {
        val base = createTempDirectory("nested-roots-base").toFile()
        markers(base, ".", "apps/web")

        assertEquals(listOf(base), nestedBuildRoots(base, CMAKE_LISTS, ::hasCMakeLists))
    }

    @Test
    fun `sibling markers two levels deep are all roots`() {
        val base = createTempDirectory("nested-siblings").toFile()
        markers(base, "apps/web", "apps/admin")

        assertEquals(
            listOf(File(base, "apps/admin"), File(base, "apps/web")),
            nestedBuildRoots(base, CMAKE_LISTS, ::hasCMakeLists),
        )
    }

    @Test
    fun `a marker three levels deep is a root`() {
        val base = createTempDirectory("nested-three").toFile()
        markers(base, "services/api/core")

        assertEquals(listOf(File(base, "services/api/core")), nestedBuildRoots(base, CMAKE_LISTS, ::hasCMakeLists))
    }

    @Test
    fun `members inside a found root are not separate roots`() {
        val base = createTempDirectory("nested-member").toFile()
        markers(base, "apps/web", "apps/web/packages/ui", "apps/admin")

        assertEquals(
            listOf(File(base, "apps/admin"), File(base, "apps/web")),
            nestedBuildRoots(base, CMAKE_LISTS, ::hasCMakeLists),
        )
    }

    @Test
    fun `skipped directories are not searched`() {
        val base = createTempDirectory("nested-skip").toFile()
        markers(base, "apps/web", "node_modules/pkg", "apps/build/out", "target/x")

        assertEquals(listOf(File(base, "apps/web")), nestedBuildRoots(base, CMAKE_LISTS, ::hasCMakeLists))
    }

    @Test
    fun `a symlinked directory is not searched`() {
        val base = createTempDirectory("nested-roots-link").toFile()
        val external = createTempDirectory("nested-roots-external").toFile()
        markers(external, "web")
        markers(base, "apps/api")
        assumeTrue(runCatching { Files.createSymbolicLink(File(base, "linked").toPath(), external.toPath()) }.isSuccess)

        assertEquals(listOf(File(base, "apps/api")), nestedBuildRoots(base, CMAKE_LISTS, ::hasCMakeLists))
    }

    @Test
    fun `roots beyond the cap are dropped and the adapter stays present for the rest`() {
        val base = createTempDirectory("nested-many-roots").toFile()
        markers(base, *(1..PerformanceBudgets.MAX_NESTED_ROOTS).map { "apps/p$it" }.toTypedArray())
        assertEquals(PerformanceBudgets.MAX_NESTED_ROOTS, nestedBuildRoots(base, CMAKE_LISTS, ::hasCMakeLists).size)

        markers(base, "apps/zz-extra")

        val capped = nestedBuildRoots(base, CMAKE_LISTS, ::hasCMakeLists)
        assertEquals(PerformanceBudgets.MAX_NESTED_ROOTS, capped.size)
        assertFalse(File(base, "apps/zz-extra") in capped)
    }

    @Test
    fun `a truncated discovery is remembered per base until a scan completes`() {
        val base = createTempDirectory("nested-caps").toFile()
        markers(base, *(1..PerformanceBudgets.MAX_NESTED_ROOTS + 1).map { "apps/p$it" }.toTypedArray())

        val scan = { nestedBuildRoots(base, CMAKE_LISTS, ::hasCMakeLists) }
        scan()

        assertTrue(NestedRootCaps.reachedUnder(base.path))
        assertTrue(NestedRootCaps.reachedUnder(base.parentFile.path))
        assertFalse(NestedRootCaps.reachedUnder(File(base, "apps").path))

        File(base, "apps/p1").deleteRecursively()
        File(base, "apps/p2").deleteRecursively()
        scan()

        assertFalse(NestedRootCaps.reachedUnder(base.path))
    }

    @Test
    fun `too many visited directories keep the roots found before the limit`() {
        val base = createTempDirectory("nested-many-dirs").toFile()
        markers(base, "apps/web")
        repeat(PerformanceBudgets.MAX_DIRECTORIES) { File(base, "empty/d$it").mkdirs() }

        assertEquals(listOf(File(base, "apps/web")), nestedBuildRoots(base, CMAKE_LISTS, ::hasCMakeLists))
    }

    @Test
    fun `an independent marker below a found root is a root of its own`() {
        val base = createTempDirectory("independent-below").toFile()
        markers(base, ".", "tools/standalone")

        assertEquals(listOf(base, File(base, "tools/standalone")), independentRoots(base, never))
    }

    @Test
    fun `a member marker below a found root is not duplicated`() {
        val base = createTempDirectory("independent-member").toFile()
        markers(base, ".", "packages/ui", "tools/standalone")

        assertEquals(
            listOf(base, File(base, "tools/standalone")),
            independentRoots(base) { _, nested -> nested.name == "ui" },
        )
    }

    @Test
    fun `membership is asked of the nearest root and the walk continues below members`() {
        val base = createTempDirectory("independent-nearest").toFile()
        markers(base, ".", "a", "a/b", "a/b/c", "x", "x/y")
        val asked = ArrayList<Pair<String, String>>()

        val roots = independentRoots(base) { root, nested ->
            asked += root.name to nested.name
            root == base && nested.name == "a" || root.name == "x"
        }

        assertEquals(listOf(base, File(base, "a/b"), File(base, "a/b/c"), File(base, "x")), roots)
        assertEquals(
            setOf(base.name to "a", base.name to "x", base.name to "b", "b" to "c", "x" to "y"),
            asked.toSet(),
        )
    }

    @Test
    fun `independent roots are found below roots discovered under a base without a marker`() {
        val base = createTempDirectory("independent-deeper").toFile()
        markers(base, "apps/web", "apps/web/tests/e2e/fixture-app")

        assertEquals(
            listOf(File(base, "apps/web"), File(base, "apps/web/tests/e2e/fixture-app")),
            independentRoots(base, never),
        )
    }

    @Test
    fun `independent roots stop at the depth and the root caps`() {
        val base = createTempDirectory("independent-limits").toFile()
        val deepest = (1..PerformanceBudgets.MAX_DEPTH).joinToString("/") { "d$it" }
        markers(base, ".", deepest, "$deepest/too-deep")
        assertEquals(listOf(base, File(base, deepest)), independentRoots(base, never))

        markers(base, *(1..PerformanceBudgets.MAX_NESTED_ROOTS + 5).map { "tools/p$it" }.toTypedArray())
        assertEquals(PerformanceBudgets.MAX_NESTED_ROOTS, independentRoots(base, never).size)
    }

    @Test
    fun `independent roots stay bounded by the directory budget`() {
        val base = createTempDirectory("independent-budget").toFile()
        markers(base, ".")
        repeat(PerformanceBudgets.MAX_DIRECTORIES + 10) { File(base, "a/d$it").mkdirs() }
        markers(base, "zz/late")

        assertEquals(listOf(base), independentRoots(base, never))
    }

    @Test
    fun `independent roots skip build output vendor test data fixture and hidden directories`() {
        val base = createTempDirectory("independent-skip").toFile()
        markers(
            base, ".", "tools/ok", "node_modules/pkg", "vendor/dep", "testdata/x", "Fixtures/x", "tools/Vendor/dep",
            ".hidden/x", "_private/x", "build/out", "target/x", "Pods/x",
        )

        assertEquals(listOf(base, File(base, "tools/ok")), independentRoots(base, never))
    }

    @Test
    fun `a symlinked directory below a found root is not searched for independent roots`() {
        val base = createTempDirectory("independent-link").toFile()
        val external = createTempDirectory("independent-external").toFile()
        markers(external, "web")
        markers(base, ".")
        assumeTrue(runCatching { Files.createSymbolicLink(File(base, "linked").toPath(), external.toPath()) }.isSuccess)

        assertEquals(listOf(base), independentRoots(base, never))
    }

    private fun independentRoots(base: File, isMember: (File, File) -> Boolean): List<File> =
        nestedBuildRoots(base, CMAKE_LISTS, isMember, ::hasCMakeLists)

    private val never: (File, File) -> Boolean = isNeverMember

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

    @Test
    fun `a settled tree is listed once and a directory change is seen after the recheck window`() {
        val base = createTempDirectory("nested-roots-cache").toFile()
        File(base, "apps/web").mkdirs()
        File(base, "apps/web/CMakeLists.txt").writeText("")
        File(base, "apps/admin").mkdirs()
        val past = System.currentTimeMillis() - 10_000
        base.walkBottomUp().filter(File::isDirectory).forEach { it.setLastModified(past) }
        var probes = 0
        val marker = { directory: File ->
            probes += 1
            hasCMakeLists(directory)
        }

        assertEquals(listOf(File(base, "apps/web")), nestedBuildRoots(base, CMAKE_LISTS, marker))
        val first = probes
        assertEquals(listOf(File(base, "apps/web")), nestedBuildRoots(base, CMAKE_LISTS, marker))
        assertEquals(2, first, "the base and the one directory that holds a marker name")
        assertEquals(first * 2, probes)

        File(base, "apps/admin/CMakeLists.txt").writeText("")
        Thread.sleep(1_100)

        assertEquals(
            listOf(File(base, "apps/admin"), File(base, "apps/web")),
            nestedBuildRoots(base, CMAKE_LISTS, marker),
        )
    }
}

private val CMAKE_LISTS = setOf("cmakelists.txt")
