package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BazelCommandTest {

    @Test
    fun `a Bazel root runs one workspace test command`() {
        assertEquals(
            listOf("bazel", "test", "//..."),
            bazelCommands(listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a production-only Bazel change builds the workspace`() {
        assertEquals(
            listOf("bazel", "build", "//..."),
            bazelCommands(listOf(".:build")).single().arguments,
        )
    }

    @Test
    fun `unknown Bazel tasks keep the workspace test command`() {
        assertEquals(
            listOf("bazel", "test", "//..."),
            bazelCommands(listOf(".:mystery")).single().arguments,
        )
    }

    @Test
    fun `a MODULE bazel root with a test rule is runnable`() {
        val root = bazelRoot("MODULE.bazel")
        File(root, "BUILD.bazel").writeText("sh_test(name = \"alpha_test\", srcs = [\"alpha_test.sh\"])")

        val module = bazelRootModule(root)
        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals("build", module.compileTask)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `a Bazel workspace without test rules is built`() {
        val root = bazelRoot("MODULE.bazel")
        File(root, "BUILD.bazel").writeText("filegroup(name = \"src\")")

        assertFalse(bazelRootModule(root).hasTests)
    }

    @Test
    fun `Gradle settings keep the root off the Bazel adapter`() {
        val root = bazelRoot("MODULE.bazel")
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mixed\"")

        assertNull(bazelManifest(root))
    }

    @Test
    fun `a Maven pom keeps the root off the Bazel adapter`() {
        val root = bazelRoot("WORKSPACE")
        File(root, "pom.xml").writeText("<project/>")

        assertNull(bazelManifest(root))
    }

    @Test
    fun `a single first-level nested Bazel workspace is the root`() {
        val base = createTempDirectory("bazel-nested").toFile()
        val nested = File(base, "backend")
        bazelRoot("MODULE.bazel").copyRecursively(nested)

        assertEquals(nested.canonicalFile, bazelProjectRoot(base)?.canonicalFile)
    }

    @Test
    fun `several first-level nested Bazel workspaces stay off`() {
        val base = createTempDirectory("bazel-many").toFile()
        bazelRoot("MODULE.bazel").copyRecursively(File(base, "backend"))
        bazelRoot("WORKSPACE").copyRecursively(File(base, "tools"))

        assertNull(bazelProjectRoot(base))
    }

    @Test
    fun `BUILD files become packages with their own content roots`() {
        val root = bazelRoot("MODULE.bazel")
        File(root, "alpha/BUILD.bazel").apply {
            parentFile.mkdirs()
            writeText("sh_test(name = \"alpha_test\", srcs = [\"alpha_test.sh\"])")
        }
        File(root, "beta/BUILD.bazel").apply {
            parentFile.mkdirs()
            writeText("filegroup(name = \"src\")")
        }

        val packages = requireNotNull(bazelPackages(root))
        assertEquals(setOf(".", "alpha", "beta"), packages.map(BuildModule::executionId).toSet())
        val alpha = packages.single { it.executionId == "alpha" }
        val beta = packages.single { it.executionId == "beta" }
        assertTrue(alpha.hasTests)
        assertFalse(beta.hasTests)
        assertEquals(File(root, "alpha").invariantSeparatorsPath, alpha.contentRoots.single())
        assertEquals(
            listOf("bazel", "test", "//alpha:all"),
            bazelCommands(listOf("${alpha.executionId}:test")).single().arguments,
        )
    }

    @Test
    fun `named Bazel packages share one test invocation`() {
        assertEquals(
            listOf("bazel", "test", "//alpha:all", "//beta:all"),
            bazelCommands(listOf("alpha:test", "beta:test")).single().arguments,
        )
    }

    @Test
    fun `one production Bazel package builds only that package`() {
        assertEquals(
            listOf("bazel", "build", "//alpha:all"),
            bazelCommands(listOf("alpha:build")).single().arguments,
        )
    }

    @Test
    fun `a root Bazel task keeps the workspace command`() {
        assertEquals(
            listOf("bazel", "test", "//..."),
            bazelCommands(listOf(".:test", "alpha:test")).single().arguments,
        )
    }

    @Test
    fun `BUILD and MODULE changes require the whole workspace`() {
        val root = bazelRoot("MODULE.bazel")
        File(root, "alpha/BUILD.bazel").apply {
            parentFile.mkdirs()
            writeText("filegroup(name = \"src\")")
        }
        val module = bazelRootModule(root)

        assertTrue(
            bazelRequiresWorkspace(
                module.root,
                BuildChanges(listOf(File(root, "MODULE.bazel").path), emptySet(), comparedToBase = true),
            ),
        )
        assertTrue(
            bazelRequiresWorkspace(
                module.root,
                BuildChanges(listOf(File(root, "alpha/BUILD.bazel").path), emptySet(), comparedToBase = true),
            ),
        )
        assertFalse(
            bazelRequiresWorkspace(
                module.root,
                BuildChanges(listOf(File(root, "alpha/src.cc").path), emptySet(), comparedToBase = true),
            ),
        )
    }

    @Test
    fun `Bazel output trees do not become packages`() {
        val root = bazelRoot("MODULE.bazel")
        File(root, "alpha/BUILD.bazel").apply {
            parentFile.mkdirs()
            writeText("sh_test(name = \"alpha_test\", srcs = [\"alpha_test.sh\"])")
        }
        File(root, "bazel-bin/BUILD.bazel").apply {
            parentFile.mkdirs()
            writeText("filegroup(name = \"generated\")")
        }

        val packages = requireNotNull(bazelPackages(root))
        assertEquals(setOf(".", "alpha"), packages.map(BuildModule::executionId).toSet())
    }

    @Test
    fun `both BUILD files in one directory keep the root module`() {
        val root = bazelRoot("MODULE.bazel")
        File(root, "alpha/BUILD").apply {
            parentFile.mkdirs()
            writeText("filegroup(name = \"legacy\")")
        }
        File(root, "alpha/BUILD.bazel").writeText("filegroup(name = \"starlark\")")

        assertEquals(
            listOf("."),
            failClosedModules(root, "test", "build", bazelPackages(root)).modules.map { it.executionId },
        )
    }

    private fun bazelRoot(marker: String): File {
        val root = createTempDirectory("bazel-root").toFile()
        File(root, marker).writeText("module(name = \"probe\")\n")
        return root
    }
}
