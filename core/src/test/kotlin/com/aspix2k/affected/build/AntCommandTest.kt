package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AntCommandTest {

    @Test
    fun `an Ant root runs one project test command`() {
        assertEquals(
            listOf("ant", "test"),
            antCommands(listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `a production-only Ant change compiles the project`() {
        assertEquals(
            listOf("ant", "compile"),
            antCommands(listOf(".:compile")).single().arguments,
        )
    }

    @Test
    fun `unknown Ant tasks keep the project test command`() {
        assertEquals(
            listOf("ant", "test"),
            antCommands(listOf(".:mystery")).single().arguments,
        )
    }

    @Test
    fun `an Ant module with a test target is runnable`() {
        val root = antRoot("<project><target name=\"test\"/></project>")

        val module = antRootModule(root)
        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals("compile", module.compileTask)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `an Ant module without a test target is compiled`() {
        val root = antRoot("<project><target name=\"compile\"/></project>")

        val module = antRootModule(root)
        assertFalse(module.hasTests)
        assertEquals("compile", module.compileTask)
    }

    @Test
    fun `a junit target is treated as the Ant test task`() {
        val root = antRoot("<project><target name=\"junit\"/></project>")

        val module = antRootModule(root)
        assertTrue(module.hasTests)
        assertEquals("junit", module.testTask)
        assertEquals(
            listOf("ant", "junit"),
            antCommands(listOf(".:${module.testTask}")).single().arguments,
        )
    }

    @Test
    fun `Gradle settings keep the root off the Ant adapter`() {
        val root = antRoot("<project><target name=\"test\"/></project>")
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mixed\"")

        assertNull(antManifest(root))
    }

    @Test
    fun `a Maven pom keeps the root off the Ant adapter`() {
        val root = antRoot("<project><target name=\"test\"/></project>")
        File(root, "pom.xml").writeText("<project/>")

        assertNull(antManifest(root))
    }

    @Test
    fun `an MPS project directory keeps the root off the Ant adapter`() {
        val root = antRoot("<project><target name=\"test\"/></project>")
        File(root, ".mps").mkdirs()

        assertNull(antManifest(root))
    }

    @Test
    fun `an MPS language module keeps the root off the Ant adapter`() {
        val root = antRoot("<project><target name=\"test\"/></project>")
        File(root, "languages/foo/foo.mpl").apply {
            parentFile.mkdirs()
            writeText("<language/>")
        }

        assertNull(antManifest(root))
    }

    @Test
    fun `an MPS solution module keeps the root off the Ant adapter`() {
        val root = antRoot("<project><target name=\"test\"/></project>")
        File(root, "solutions/bar/bar.msd").apply {
            parentFile.mkdirs()
            writeText("<solution/>")
        }

        assertNull(antManifest(root))
    }

    @Test
    fun `an imported file contributes its test target`() {
        val root = antRoot("<project><import file=\"testdefs.xml\"/></project>")
        File(root, "testdefs.xml").writeText("<project><target name=\"test\"/></project>")
        val module = antRootModule(root)

        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals(listOf("ant", "test"), antCommands(listOf(".:test")).single().arguments)
    }

    @Test
    fun `an unproved import keeps the test command`() {
        val root = antRoot("<project><import file=\"\${defs}\"/><target name=\"compile\"/></project>")
        val module = antRootModule(root)

        assertTrue(module.hasTests)
        assertEquals(listOf("ant", "test"), antCommands(listOf(".:test")).single().arguments)
    }

    @Test
    fun `a missing import keeps the test command`() {
        val root = antRoot("<project><import file=\"missing.xml\"/><target name=\"compile\"/></project>")
        val module = antRootModule(root)

        assertTrue(module.hasTests)
        assertEquals(listOf("ant", "test"), antCommands(listOf(".:test")).single().arguments)
    }

    @Test
    fun `a documented junit token in the target body keeps the task runnable`() {
        val root = antRoot(
            """
            <project>
              <target name="run-tests">
                <echo>AlphaTest<![CDATA[ <junit/> ]]></echo>
              </target>
            </project>
            """.trimIndent(),
        )
        val module = antRootModule(root)

        assertTrue(module.hasTests)
        assertEquals("run-tests", module.testTask)
    }

    @Test
    fun `a junit task target is treated as the Ant test task`() {
        val root = antRoot(
            """
            <project>
              <target name="run-tests">
                <junit fork="true"/>
              </target>
            </project>
            """.trimIndent(),
        )
        val module = antRootModule(root)

        assertTrue(module.hasTests)
        assertEquals("run-tests", module.testTask)
        assertEquals(
            listOf("ant", "run-tests"),
            antCommands(root, listOf(".:${module.testTask}")).single().arguments,
        )
    }

    @Test
    fun `a testng task target is treated as the Ant test task`() {
        val root = antRoot(
            """
            <project>
              <target name="verify">
                <testng/>
              </target>
            </project>
            """.trimIndent(),
        )
        val module = antRootModule(root)

        assertTrue(module.hasTests)
        assertEquals("verify", module.testTask)
        assertEquals(listOf("ant", "verify"), antCommands(root, listOf(".:verify")).single().arguments)
    }

    @Test
    fun `a named test target still wins over a junit task`() {
        val root = antRoot(
            """
            <project>
              <target name="test"/>
              <target name="run-tests">
                <junit/>
              </target>
            </project>
            """.trimIndent(),
        )

        assertEquals("test", antRootModule(root).testTask)
    }

    @Test
    fun `a generate target runs before test when test does not depend on it`() {
        val root = antRoot(
            """
            <project>
              <target name="generate"/>
              <target name="test"/>
            </project>
            """.trimIndent(),
        )

        assertEquals(
            listOf(listOf("ant", "generate"), listOf("ant", "test")),
            antCommands(root, listOf(".:test")).map(CliCommand::arguments),
        )
    }

    @Test
    fun `a test that depends on generate does not prepend generate`() {
        val root = antRoot(
            """
            <project>
              <target name="generate"/>
              <target name="test" depends="generate"/>
            </project>
            """.trimIndent(),
        )

        assertEquals(
            listOf(listOf("ant", "test")),
            antCommands(root, listOf(".:test")).map(CliCommand::arguments),
        )
    }

    @Test
    fun `an unproved depends keeps generate before test`() {
        val root = antRoot(
            """
            <project>
              <target name="generate"/>
              <target name="test" depends="\${'$'}{prep}"/>
            </project>
            """.trimIndent(),
        )

        assertEquals(
            listOf(listOf("ant", "generate"), listOf("ant", "test")),
            antCommands(root, listOf(".:test")).map(CliCommand::arguments),
        )
    }

    @Test
    fun `a static property expands an imported test target`() {
        val root = antRoot(
            """
            <project>
              <property name="defs" value="testdefs.xml"/>
              <import file="&dollar;{defs}"/>
            </project>
            """.trimIndent().replace("&dollar;", "$"),
        )
        File(root, "testdefs.xml").writeText("<project><target name=\"test\"/></project>")
        val module = antRootModule(root)

        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals(listOf("ant", "test"), antCommands(listOf(".:test")).single().arguments)
    }

    @Test
    fun `a property file expands an imported test target`() {
        val root = antRoot(
            """
            <project>
              <property file="build.properties"/>
              <import file="&dollar;{defs}"/>
            </project>
            """.trimIndent().replace("&dollar;", "$"),
        )
        File(root, "build.properties").writeText("defs=testdefs.xml\n")
        File(root, "testdefs.xml").writeText("<project><target name=\"test\"/></project>")
        val module = antRootModule(root)

        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
    }

    @Test
    fun `an antcall keeps the test command`() {
        val root = antRoot(
            """
            <project>
              <target name="compile"/>
              <target name="run"><antcall target="hidden-test"/></target>
            </project>
            """.trimIndent(),
        )
        val module = antRootModule(root)

        assertTrue(module.hasTests)
        assertEquals(listOf("ant", "test"), antCommands(root, listOf(".:test")).single().arguments)
    }

    @Test
    fun `a nested ant task keeps the test command`() {
        val root = antRoot(
            """
            <project>
              <target name="compile"/>
              <target name="run"><ant antfile="more.xml"/></target>
            </project>
            """.trimIndent(),
        )
        val module = antRootModule(root)

        assertTrue(module.hasTests)
        assertEquals(listOf("ant", "test"), antCommands(root, listOf(".:test")).single().arguments)
    }

    @Test
    fun `an unproved target name keeps the test command`() {
        val root = antRoot(
            """
            <project>
              <target name="compile"/>
              <target name="&dollar;{suite}"/>
            </project>
            """.trimIndent().replace("&dollar;", "$"),
        )
        val module = antRootModule(root)

        assertTrue(module.hasTests)
        assertEquals(listOf("ant", "test"), antCommands(root, listOf(".:test")).single().arguments)
    }

    @Test
    fun `a target condition keeps the test command`() {
        val root = antRoot(
            """
            <project>
              <target name="compile"/>
              <target name="maybe" if="run.tests"><junit/></target>
            </project>
            """.trimIndent(),
        )
        val module = antRootModule(root)

        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals(
            listOf("ant", "test"),
            antCommands(root, listOf(".:${module.testTask}")).single().arguments,
        )
    }

    @Test
    fun `an optional missing import does not invent tests`() {
        val root = antRoot(
            "<project><import file=\"missing.xml\" optional=\"true\"/><target name=\"compile\"/></project>",
        )
        val module = antRootModule(root)

        assertFalse(module.hasTests)
        assertEquals("compile", module.compileTask)
    }

    private fun antRoot(buildXml: String): File {
        val root = createTempDirectory("ant-root").toFile()
        File(root, "build.xml").writeText(buildXml)
        return root
    }
}
