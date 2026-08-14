package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinToolchainCommandTest {

    @Test
    fun `a toolchain root runs one project test command`() {
        val root = toolchainRoot()

        assertEquals(
            listOf(kotlinToolchainWrapper(root), "test"),
            kotlinToolchainCommands(root, listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a production-only toolchain change builds the project`() {
        val root = toolchainRoot()

        assertEquals(
            listOf(kotlinToolchainWrapper(root), "build"),
            kotlinToolchainCommands(root, listOf(".:build")).single().arguments,
        )
    }

    @Test
    fun `unknown toolchain tasks keep the project test command`() {
        val root = toolchainRoot()

        assertEquals(
            listOf(kotlinToolchainWrapper(root), "test"),
            kotlinToolchainCommands(root, listOf(".:mystery")).single().arguments,
        )
    }

    @Test
    fun `a toolchain module with test sources is runnable`() {
        val root = toolchainRoot()
        File(root, "test/AlphaTest.kt").apply {
            parentFile.mkdirs()
            writeText("class AlphaTest")
        }

        val module = kotlinToolchainRootModule(root)
        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals("build", module.compileTask)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `a toolchain module without tests is built`() {
        val root = toolchainRoot()
        File(root, "src/Alpha.kt").apply {
            parentFile.mkdirs()
            writeText("class Alpha")
        }

        assertFalse(kotlinToolchainRootModule(root).hasTests)
    }

    @Test
    fun `yaml without a kotlin wrapper is not a toolchain root`() {
        val root = createTempDirectory("toolchain-yaml").toFile()
        File(root, "module.yaml").writeText("product: jvm/lib")

        assertNull(kotlinToolchainManifest(root))
    }

    @Test
    fun `a wrapper without yaml is not a toolchain root`() {
        val root = createTempDirectory("toolchain-wrapper").toFile()
        File(root, "kotlin").writeText("#!/bin/sh\n")

        assertNull(kotlinToolchainManifest(root))
    }

    @Test
    fun `an amper wrapper is not a toolchain root`() {
        val root = createTempDirectory("toolchain-amper").toFile()
        File(root, "module.yaml").writeText("product: jvm/lib")
        File(root, "amper").writeText("#!/bin/sh\n")

        assertNull(kotlinToolchainManifest(root))
    }

    @Test
    fun `a Gradle settings file keeps the root off the toolchain adapter`() {
        val root = toolchainRoot()
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mixed\"")

        assertNull(kotlinToolchainManifest(root))
    }

    @Test
    fun `project yaml lists explicit modules with their own content roots`() {
        val root = toolchainRoot()
        File(root, "project.yaml").writeText("modules:\n  - ./app\n  - ./lib\n")
        File(root, "app/module.yaml").apply {
            parentFile.mkdirs()
            writeText("product: jvm/app")
        }
        File(root, "app/test/AppTest.kt").apply {
            parentFile.mkdirs()
            writeText("class AppTest")
        }
        File(root, "lib/module.yaml").apply {
            parentFile.mkdirs()
            writeText("product: jvm/lib")
        }

        val modules = requireNotNull(kotlinToolchainModules(root))
        assertEquals(listOf(".", "app", "lib"), modules.map(BuildModule::executionId))
        assertEquals(listOf(false, true, false), modules.map(BuildModule::hasTests))
        assertEquals(File(root, "app").invariantSeparatorsPath, modules[1].contentRoots.single())
        assertEquals(
            listOf(kotlinToolchainWrapper(root), "test", "-m", "app"),
            kotlinToolchainCommands(root, modules.filter(BuildModule::hasTests).map { "${it.executionId}:test" })
                .single()
                .arguments,
        )
    }

    @Test
    fun `a glob in project yaml keeps the root module`() {
        val root = toolchainRoot()
        File(root, "project.yaml").writeText("modules:\n  - ./plugins/*\n")
        File(root, "plugins/one/module.yaml").apply {
            parentFile.mkdirs()
            writeText("product: jvm/lib")
        }

        val modules = requireNotNull(kotlinToolchainModules(root))
        assertEquals(listOf("."), modules.map(BuildModule::executionId))
    }

    @Test
    fun `a project yaml change requires the whole workspace`() {
        val root = toolchainRoot()
        File(root, "project.yaml").writeText("modules:\n  - ./app\n")
        val module = kotlinToolchainRootModule(root)

        assertTrue(
            kotlinToolchainRequiresWorkspace(
                module.root,
                BuildChanges(listOf(File(root, "project.yaml").path), emptySet(), comparedToBase = true),
            ),
        )
        assertFalse(
            kotlinToolchainRequiresWorkspace(
                module.root,
                BuildChanges(listOf(File(root, "src/Alpha.kt").path), emptySet(), comparedToBase = true),
            ),
        )
    }

    @Test
    fun `named toolchain modules share one -m test invocation`() {
        val root = toolchainRoot()

        assertEquals(
            listOf(kotlinToolchainWrapper(root), "test", "-m", "app", "-m", "lib"),
            kotlinToolchainCommands(root, listOf("app:test", "lib:test")).single().arguments,
        )
    }

    @Test
    fun `a root toolchain task keeps the unscoped project command`() {
        val root = toolchainRoot()

        assertEquals(
            listOf(kotlinToolchainWrapper(root), "test"),
            kotlinToolchainCommands(root, listOf("app:test", ".:test")).single().arguments,
        )
    }

    @Test
    fun `one named production module builds with -m`() {
        val root = toolchainRoot()

        assertEquals(
            listOf(kotlinToolchainWrapper(root), "build", "-m", "lib"),
            kotlinToolchainCommands(root, listOf("lib:build")).single().arguments,
        )
    }

    @Test
    fun `an unversioned wrapper keeps the unscoped test command`() {
        val root = toolchainRoot(version = null)

        assertEquals(
            listOf(kotlinToolchainWrapper(root), "test"),
            kotlinToolchainCommands(root, listOf("app:test")).single().arguments,
        )
    }

    @Test
    fun `an unproven toolchain version keeps the unscoped test command`() {
        val root = toolchainRoot(version = "0.12.0")

        assertEquals(
            listOf(kotlinToolchainWrapper(root), "test"),
            kotlinToolchainCommands(root, listOf("app:test")).single().arguments,
        )
    }

    @Test
    fun `several production modules keep the unscoped build command`() {
        val root = toolchainRoot()

        assertEquals(
            listOf(kotlinToolchainWrapper(root), "build"),
            kotlinToolchainCommands(root, listOf("app:build", "lib:build")).single().arguments,
        )
    }

    private fun toolchainRoot(version: String? = "0.11.0"): File {
        val root = createTempDirectory("toolchain-root").toFile()
        File(root, "module.yaml").writeText("product: jvm/lib")
        val pin = version?.let { "kotlin_cli_version=$it\n" }.orEmpty()
        File(root, "kotlin").writeText("#!/bin/sh\n$pin")
        File(root, "kotlin.bat").writeText("@echo off\n${version?.let { "set kotlin_cli_version=$it\n" }.orEmpty()}")
        return root
    }
}
