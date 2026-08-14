package com.aspix2k.affected.build

import java.io.File
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
    fun `an ignored first-level directory is not a root`() {
        val base = createTempDirectory("nested-ignored").toFile()
        File(base, "node_modules").mkdirs()
        File(base, "node_modules/CMakeLists.txt").writeText("")

        assertNull(nestedBuildRoot(base, ::hasCMakeLists))
    }

    private fun hasCMakeLists(directory: File): Boolean =
        File(directory, "CMakeLists.txt").isRegularFileNoFollow()
}
