package com.aspix2k.affected

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineTest {

    @Test
    fun `a change against the base branch plans the owning package tests`() {
        val root = repository()
        File(root, "index.js").appendText("// changed\n")

        val plan = Engine.plan(request(root, "main"))

        assertNull(plan.blocker)
        assertEquals(listOf(File(root, "index.js")), plan.changedFiles)
        assertEquals(listOf("NODE"), plan.systems.map { it.id })
        assertEquals(listOf("NODE"), plan.plan.groups.map { it.systemId })
        assertTrue(plan.plan.groups.single().tasks.single().endsWith(":test"))
    }

    @Test
    fun `an unchanged branch plans nothing and is not blocked`() {
        val plan = Engine.plan(request(repository(), "main"))

        assertNull(plan.blocker)
        assertTrue(plan.plan.isEmpty)
    }

    @Test
    fun `a base branch that does not exist blocks instead of comparing with another one`() {
        val root = repository()
        File(root, "index.js").appendText("// changed\n")

        assertEquals(EngineBlocker.NO_COMPARISON_BASE, Engine.plan(request(root, "release")).blocker)
    }

    @Test
    fun `a repository with a Gradle or Maven build is blocked instead of passing with its sources ignored`() {
        val root = repository()
        File(root, "service/build.gradle.kts").apply { parentFile.mkdirs() }.writeText("plugins { java }")
        File(root, "service/Main.java").writeText("class Main {}")

        assertEquals(EngineBlocker.UNSUPPORTED_BUILD_SYSTEM, Engine.plan(request(root, "main")).blocker)
    }

    @Test
    fun `a repository without any known build is blocked instead of passing`() {
        val root = repository()
        File(root, "package.json").delete()

        assertEquals(EngineBlocker.NO_BUILD_SYSTEM, Engine.plan(request(root, "main")).blocker)
    }

    @Test
    fun `a directory outside git is blocked`() {
        val root = createTempDirectory("engine-plain").toFile()
        File(root, "package.json").writeText(MANIFEST)

        assertEquals(EngineBlocker.NOT_A_GIT_REPOSITORY, Engine.plan(request(root, "main")).blocker)
    }

    private fun request(root: File, base: String) =
        EngineRequest(root, base, createTempDirectory("engine-cache"))

    private fun repository(): File {
        val root = createTempDirectory("engine-repository").toFile().canonicalFile
        File(root, "package.json").writeText(MANIFEST)
        File(root, "index.js").writeText("module.exports = 1\n")
        File(root, "index.test.js").writeText("require('./index.js')\n")
        git(root, "init", "--quiet", "--initial-branch=main")
        git(root, "add", ".")
        git(root, "-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-m", "init")
        git(root, "checkout", "--quiet", "-b", "feature")
        return root
    }

    private fun git(root: File, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git") + arguments).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }

    private companion object {
        const val MANIFEST = """{"name":"demo","version":"1.0.0","scripts":{"test":"node --test"}}"""
    }
}
