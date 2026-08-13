package com.aspix2k.affected

import com.aspix2k.affected.build.ComposerBuildSystem
import com.aspix2k.affected.build.PythonBuildSystem
import com.intellij.openapi.project.Project
import java.io.File
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildSystemCacheTest {

    @Test
    fun `cache entries never cross project roots`() {
        val first = projectRoot("first")
        val second = projectRoot("second")
        val timestamp = System.currentTimeMillis() - 10_000
        assertTrue(File(first, "pyproject.toml").setLastModified(timestamp))
        assertTrue(File(second, "pyproject.toml").setLastModified(timestamp))
        val system = PythonBuildSystem()

        assertEquals(setOf("first-root", "first-child"), system.modules(project(first)).map { it.id }.toSet())
        assertEquals(setOf("second-root", "second-child"), system.modules(project(second)).map { it.id }.toSet())
    }

    @Test
    fun `a child manifest change invalidates the cache even with the same timestamp`() {
        val root = projectRoot("first")
        val child = File(root, "child/pyproject.toml")
        val timestamp = child.lastModified()
        val system = PythonBuildSystem()
        assertEquals(setOf("first-root", "first-child"), system.modules(project(root)).map { it.id }.toSet())
        child.writeText("[project]\nname = \"other-child\"\n")
        assertTrue(child.setLastModified(timestamp))

        assertEquals(setOf("first-root", "other-child"), system.modules(project(root)).map { it.id }.toSet())
    }

    @Test
    fun `a new test directory invalidates cached module capabilities`() {
        val root = projectRoot("layout")
        val system = PythonBuildSystem()
        assertTrue(system.modules(project(root)).none { it.hasTests })

        File(root, "child/tests").mkdirs()

        assertTrue(system.modules(project(root)).single { it.id == "layout-child" }.hasTests)
    }

    @Test
    fun `a new Pest test file invalidates cached module capabilities`() {
        val source = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "conformance/cli-fixtures/pest") }
            .first(File::isDirectory)
        val root = createTempDirectory("cache-pest").toFile()
        assertTrue(source.copyRecursively(root, overwrite = true))
        val system = ComposerBuildSystem()
        assertTrue(system.modules(project(root)).single { it.id == "affected/pest-fixture-root" }.hasTests.not())

        File(root, "tests/RootTest.php").writeText("<?php\n")

        assertTrue(system.modules(project(root)).single { it.id == "affected/pest-fixture-root" }.hasTests)
    }

    @Test
    fun `a deep Pest test file invalidates cached module capabilities`() {
        val source = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "conformance/cli-fixtures/pest") }
            .first(File::isDirectory)
        val root = createTempDirectory("cache-deep-pest").toFile()
        assertTrue(source.copyRecursively(root, overwrite = true))
        val deepSuite = (1..10).fold(File(root, "tests/deep")) { directory, depth -> File(directory, "d$depth") }
        assertTrue(deepSuite.mkdirs())
        val system = ComposerBuildSystem()
        assertTrue(system.modules(project(root)).single { it.id == "affected/pest-fixture-root" }.hasTests.not())

        File(deepSuite, "RootTest.php").writeText("<?php\n")

        assertTrue(system.modules(project(root)).single { it.id == "affected/pest-fixture-root" }.hasTests)
    }

    private fun projectRoot(prefix: String): File = createTempDirectory("cache-$prefix").toFile().apply {
        File(this, "pyproject.toml").writeText("[project]\nname = \"$prefix-root\"\n")
        File(this, "child").mkdirs()
        File(this, "child/pyproject.toml").writeText("[project]\nname = \"$prefix-child\"\n")
    }

    private fun project(root: File): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getBasePath" -> root.path
            else -> error("Unexpected Project call: ${method.name}")
        }
    } as Project
}
