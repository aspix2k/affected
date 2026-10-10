package com.aspix2k.affected

import com.aspix2k.affected.build.AllFileChangesBuildSystem
import com.aspix2k.affected.build.EngineBuildSystems
import com.aspix2k.affected.build.capability
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

    @Test
    fun `a changed file that is not source code still plans the tests of its project`() {
        val root = createTempDirectory("engine-make").toFile().canonicalFile
        File(root, "Makefile").writeText("test:\n\t./run-tests testdata/input.json\n")
        File(root, "testdata/input.json").apply { parentFile.mkdirs() }.writeText("{}\n")
        commit(root)
        File(root, "testdata/input.json").writeText("{\"changed\": true}\n")

        val plan = Engine.plan(request(root, "main"))

        assertNull(plan.blocker)
        assertEquals(listOf(File(root, "testdata/input.json")), plan.changedFiles)
        assertEquals(listOf("MAKE"), plan.plan.groups.map { it.systemId })
    }

    @Test
    fun `a changed file outside the source roots stays out of a project that collects by source root`() {
        val root = createTempDirectory("engine-mixed").toFile().canonicalFile
        File(root, "package.json").writeText(MANIFEST)
        File(root, "pom.xml").writeText(POM)
        File(root, "src/test/java/DemoTest.java").apply { parentFile.mkdirs() }.writeText("class DemoTest {}\n")
        File(root, "src/test/resources/input.txt").apply { parentFile.mkdirs() }.writeText("one\n")
        File(root, "notes.txt").writeText("one\n")
        commit(root)

        File(root, "notes.txt").writeText("two\n")
        assertEquals(listOf("NODE"), Engine.plan(request(root, "main")).plan.groups.map { it.systemId })

        File(root, "src/test/resources/input.txt").writeText("two\n")
        assertEquals(
            setOf("NODE", "MAVEN"),
            Engine.plan(request(root, "main")).plan.groups.mapTo(HashSet()) { it.systemId },
        )
    }

    @Test
    fun `a changed file that only a project collecting by source root contains still plans its tests`() {
        val root = createTempDirectory("engine-mixed-nested").toFile().canonicalFile
        File(root, "web/package.json").apply { parentFile.mkdirs() }.writeText(MANIFEST)
        File(root, "pom.xml").writeText(POM)
        File(root, "src/test/java/DemoTest.java").apply { parentFile.mkdirs() }.writeText("class DemoTest {}\n")
        File(root, "notes.txt").writeText("one\n")
        commit(root)

        File(root, "notes.txt").writeText("two\n")

        assertEquals(listOf("MAVEN"), Engine.plan(request(root, "main")).plan.groups.map { it.systemId })
    }

    @Test
    fun `a file declared as a dependency plans the tests of the declared module`() {
        val root = packages()
        DeclaredDependencies.write(root, listOf(DeclaredDependency("shared.txt", "NODE", "web", moduleOf(root, "web"))))
        commit(root)

        File(root, "shared.txt").writeText("two\n")

        assertEquals(listOf(listOf("${moduleOf(root, "web")}:test")), tasks(root))
    }

    @Test
    fun `a dependency on a module that no longer exists plans every test`() {
        val root = packages()
        DeclaredDependencies.write(root, listOf(DeclaredDependency("shared.txt", "NODE", "gone", "gone")))
        commit(root)

        File(root, "shared.txt").writeText("two\n")

        assertEquals(2, tasks(root).flatten().size)
    }

    @Test
    fun `declared dependencies that cannot be read plan every test for any change`() {
        val root = packages()
        File(root, DeclaredDependencies.LOCATION).apply { parentFile.mkdirs() }.writeText("{")
        commit(root)

        File(root, "shared.txt").writeText("two\n")

        assertEquals(2, tasks(root).flatten().size)
    }

    private fun packages(): File {
        val root = createTempDirectory("engine-declared").toFile().canonicalFile
        for (name in listOf("web", "api")) {
            File(root, "$name/package.json").apply { parentFile.mkdirs() }.writeText(MANIFEST.replace("demo", name))
            File(root, "$name/index.js").writeText("module.exports = 1\n")
        }
        File(root, "shared.txt").writeText("one\n")
        return root
    }

    private fun tasks(root: File): List<List<String>> = Engine.plan(request(root, "main")).plan.groups.map { it.tasks }

    private fun moduleOf(root: File, directory: String): String =
        Engine.plan(request(root, "main")).everyTest.groups.single { File(it.root) == File(root, directory) }
            .tasks.single().removeSuffix(":test")

    @Test
    fun `every project directory adapter counts any changed file inside a module`() {
        assertEquals(
            emptyList(),
            EngineBuildSystems.adapters().filter { it.capability<AllFileChangesBuildSystem>() == null }.map { it.id },
        )
    }

    private fun request(root: File, base: String) =
        EngineRequest(root, base, createTempDirectory("engine-cache"))

    private fun repository(): File {
        val root = createTempDirectory("engine-repository").toFile().canonicalFile
        File(root, "package.json").writeText(MANIFEST)
        File(root, "index.js").writeText("module.exports = 1\n")
        File(root, "index.test.js").writeText("require('./index.js')\n")
        commit(root)
        return root
    }

    private fun commit(root: File) {
        git(root, "init", "--quiet", "--initial-branch=main")
        git(root, "add", ".")
        git(root, "-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-m", "init")
        git(root, "checkout", "--quiet", "-b", "feature")
    }

    private fun git(root: File, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git") + arguments).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }

    private companion object {
        const val POM = """<project><modelVersion>4.0.0</modelVersion>
            <groupId>demo</groupId><artifactId>demo</artifactId><version>1</version></project>"""
        const val MANIFEST = """{"name":"demo","version":"1.0.0","scripts":{"test":"node --test"}}"""
    }
}
