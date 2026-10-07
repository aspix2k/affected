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

        val modules = checkNotNull(gradleCommandLineModules(root, model)).associateBy { it.id }

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

        val module = checkNotNull(gradleCommandLineModules(root, model)).single()

        assertEquals("test", module.testTask)
        assertEquals(setOf("jvmTest"), module.additionalTestTasks)
        assertFalse("computeUsageTest" in module.extraTasks)
    }

    @Test
    fun `a composite build or an unreadable model is not turned into modules`() {
        val root = createTempDirectory("gradle-model-composite").toFile().canonicalFile
        val project = """{"path":":","directory":"$root","tasks":[],"tests":[],"sources":[],"testSources":[]}"""

        assertNull(gradleCommandLineModules(root, model(root, project, includedBuilds = 1)))
        assertNull(gradleCommandLineModules(root, JsonParser.parseString("""{"projects":[]}""").asJsonObject))
        assertNull(gradleCommandLineModules(root, JsonParser.parseString("""{"includedBuilds":0}""").asJsonObject))
    }

    @Test
    fun `the wrapper of the build is preferred over a Gradle on the path`() {
        val root = createTempDirectory("gradle-launcher").toFile().canonicalFile
        assertEquals("gradle", gradleLauncher(root))

        val wrapper = File(root, if (File.separatorChar == '\\') "gradlew.bat" else "gradlew").apply { writeText("") }

        assertEquals(wrapper.absolutePath, gradleLauncher(root))
    }

    private fun model(root: File, projects: String, includedBuilds: Int = 0) = JsonParser.parseString(
        """{"includedBuilds":$includedBuilds,"projects":[${projects.replace("\\", "/")}]}""",
    ).asJsonObject.also { check(root.isDirectory) }
}
