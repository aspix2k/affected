package com.aspix2k.affected.build.gradle

import com.google.gson.JsonParser
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GradleCommandLineModulesTest {

    @Test
    fun `projects become modules with their test tasks, source roots and project dependencies`() {
        val root = createTempDirectory("gradle-model").toFile().canonicalFile
        File(root, "core/src/test/java/CoreTest.java").apply { parentFile.mkdirs() }.writeText("class CoreTest {}")
        File(root, "app/src/test/java/AppTest.java").apply { parentFile.mkdirs() }.writeText("class AppTest {}")
        val model = model(
            root,
            """
            {"path":":","directory":"$root","tasks":["help"],"tests":[],"sources":[],"testSources":[],"dependencies":[]},
            {"path":":core","directory":"$root/core","tasks":["test","compileTestJava","compileJava"],"tests":["test"],
             "sources":["$root/core/src/main/java","$root/core/src/main/resources"],
             "testSources":["$root/core/src/test/java"],"dependencies":[]},
            {"path":":app","directory":"$root/app","tasks":["test","compileTestJava","compileJava"],"tests":["test"],
             "sources":["$root/app/src/main/java"],"testSources":["$root/app/src/test/java"],"dependencies":[":core"]}
            """,
        )

        val modules = checkNotNull(gradleCommandLineModules(root, GradleBuild(null, root), model)).associateBy { it.id }

        assertEquals(setOf("", ":core", ":app"), modules.keys)
        val core = modules.getValue(":core")
        val app = modules.getValue(":app")
        assertEquals("test", core.testTask)
        assertTrue(core.hasTests)
        assertEquals(root.invariantSeparatorsPath, core.executionRoot)
        assertEquals(":core", core.executionId)
        assertEquals("$root/core", core.contentRoots.first())
        assertEquals(
            listOf("$root/core/src/main/java", "$root/core/src/main/resources", "$root/core/src/test/java"),
            GradleCommandLineBuildSystem().sourceRoots(core),
        )
        assertEquals(setOf(core.key), app.dependencies)
        assertFalse(modules.getValue("").hasTests)
    }

    @Test
    fun `a task that is only named like a test is not run as one`() {
        val root = createTempDirectory("gradle-model-names").toFile().canonicalFile
        File(root, "src/test/java/LibTest.java").apply { parentFile.mkdirs() }.writeText("class LibTest {}")
        val model = model(
            root,
            """
            {"path":":","directory":"$root","tasks":["test","jvmTest","computeUsageTest","compileTestJava"],
             "tests":["test","jvmTest"],"sources":[],"testSources":["$root/src/test/java"],"dependencies":[]}
            """,
        )

        val module = checkNotNull(gradleCommandLineModules(root, GradleBuild(null, root), model)).single()

        assertEquals("test", module.testTask)
        assertEquals(setOf("jvmTest"), module.additionalTestTasks)
        assertFalse("computeUsageTest" in module.extraTasks)
    }

    @Test
    fun `an included build runs from the root under its composite path and feeds the build that includes it`() {
        val root = createTempDirectory("gradle-model-composite").toFile().canonicalFile
        val library = File(root, "library").apply { File(this, "src/test/java").mkdirs() }
        File(library, "src/test/java/LibTest.java").writeText("class LibTest {}")
        val main = model(
            root,
            """{"path":":","directory":"$root","tasks":["compileJava"],"tests":[],"sources":[],"testSources":[]}""",
            included = """{"name":"library","directory":"$library"}""",
        )
        val included = model(
            root,
            """
            {"path":":","directory":"$library","tasks":["test","compileTestJava"],"tests":["test"],
             "sources":[],"testSources":["$library/src/test/java"],"dependencies":[]}
            """,
        )

        val builds = checkNotNull(gradleIncludedBuilds(main))
        val own = checkNotNull(gradleCommandLineModules(root, GradleBuild(null, root), main))
        val provided = checkNotNull(gradleCommandLineModules(root, builds.single(), included))
        val modules = gradleCompositeDependencies(listOf(own to listOf(library), provided to emptyList()))

        val lib = modules.single { it.root == library.invariantSeparatorsPath }
        assertEquals(root.invariantSeparatorsPath, lib.executionRoot)
        assertEquals(":library", lib.executionId)
        assertEquals(setOf(lib.key), modules.single { it.root == root.invariantSeparatorsPath }.dependencies)
    }

    @Test
    fun `an unreadable model is not turned into modules`() {
        val root = createTempDirectory("gradle-model-broken").toFile().canonicalFile
        fun json(text: String) = JsonParser.parseString(text).asJsonObject

        assertNull(gradleCommandLineModules(root, GradleBuild(null, root), json("""{"projects":[]}""")))
        assertNull(gradleCommandLineModules(root, GradleBuild(null, root), json("{}")))
        assertNull(gradleIncludedBuilds(json("""{"includedBuilds":1}""")))
        assertNull(gradleIncludedBuilds(json("""{"includedBuilds":[{"name":"","directory":"x"}]}""")))
    }

    @Test
    fun `the wrapper of the build is preferred over a Gradle on the path`() {
        val root = createTempDirectory("gradle-launcher").toFile().canonicalFile
        assertEquals("gradle", gradleLauncher(root))

        val wrapper = File(root, if (File.separatorChar == '\\') "gradlew.bat" else "gradlew").apply { writeText("") }

        assertEquals(wrapper.absolutePath, gradleLauncher(root))
    }

    private fun model(root: File, projects: String, included: String = "") = JsonParser.parseString(
        """{"includedBuilds":[${included.replace("\\", "/")}],"projects":[${projects.replace("\\", "/")}]}""",
    ).asJsonObject.also { check(root.isDirectory) }
}
