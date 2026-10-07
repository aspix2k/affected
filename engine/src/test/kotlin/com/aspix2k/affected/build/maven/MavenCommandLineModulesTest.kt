package com.aspix2k.affected.build.maven

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MavenCommandLineModulesTest {

    @Test
    fun `a reactor becomes modules selected by group and artifact with reactor dependencies`() {
        val root = reactor()

        val modules = checkNotNull(mavenCommandLineModules(root)).associateBy { it.id }

        assertEquals(setOf("demo-parent", "demo-core", "demo-app"), modules.keys)
        val core = modules.getValue("demo-core")
        val app = modules.getValue("demo-app")
        assertEquals("demo:demo-core", core.executionId)
        assertEquals(File(root, "core").invariantSeparatorsPath, core.contentRoots.single())
        assertEquals("test", core.testTask)
        assertTrue(core.hasTests)
        assertFalse(modules.getValue("demo-parent").hasTests)
        assertEquals(setOf(core.key), app.dependencies)
        assertEquals(
            listOf(File(root, "core/src/test").invariantSeparatorsPath),
            MavenCommandLineBuildSystem().sourceRoots(core),
        )
    }

    @Test
    fun `a dependency declared by the parent or in a profile counts for the child`() {
        val root = reactor(
            parentExtra = "<dependencies><dependency><groupId>demo</groupId>" +
                "<artifactId>demo-core</artifactId></dependency></dependencies>",
            appDependencies = "<profiles><profile><id>x</id><dependencies><dependency>" +
                "<groupId>\${project.groupId}</groupId><artifactId>demo-core</artifactId>" +
                "</dependency></dependencies></profile></profiles>",
        )

        val modules = checkNotNull(mavenCommandLineModules(root)).associateBy { it.id }

        assertEquals(setOf(modules.getValue("demo-core").key), modules.getValue("demo-app").dependencies)
        assertEquals(setOf(modules.getValue("demo-core").key), modules.getValue("demo-parent").dependencies)
    }

    @Test
    fun `failsafe anywhere in the reactor makes every module run verify`() {
        val root = reactor(
            parentExtra = "<build><plugins><plugin><artifactId>maven-failsafe-plugin</artifactId></plugin>" +
                "</plugins></build>",
        )

        assertEquals(setOf("verify"), checkNotNull(mavenCommandLineModules(root)).mapTo(HashSet()) { it.testTask })
    }

    @Test
    fun `a reactor that cannot be read with certainty is not turned into modules`() {
        assertNull(mavenCommandLineModules(reactor(coreArtifact = "\${core.name}")))
        assertNull(mavenCommandLineModules(reactor(coreArtifact = "demo-app")))
        assertNull(mavenCommandLineModules(reactor().also { File(it, "core/pom.xml").writeText("<project>") }))
        assertNull(mavenCommandLineModules(reactor().also { File(it, "app/pom.xml").delete() }))
    }

    @Test
    fun `the wrapper of the build is preferred over a Maven on the path`() {
        val root = createTempDirectory("maven-launcher").toFile().canonicalFile
        assertEquals("mvn", mavenLauncher(root))

        val wrapper = File(root, if (File.separatorChar == '\\') "mvnw.cmd" else "mvnw").apply { writeText("") }

        assertEquals(wrapper.absolutePath, mavenLauncher(root))
    }

    private fun reactor(
        parentExtra: String = "",
        appDependencies: String = "<dependencies><dependency><groupId>demo</groupId>" +
            "<artifactId>demo-core</artifactId></dependency></dependencies>",
        coreArtifact: String = "demo-core",
    ): File {
        val root = createTempDirectory("maven-reactor").toFile().canonicalFile
        val parent = "<parent><groupId>demo</groupId><artifactId>demo-parent</artifactId><version>1</version></parent>"
        File(root, "pom.xml").writeText(
            "<project><groupId>demo</groupId><artifactId>demo-parent</artifactId><version>1</version>" +
                "<packaging>pom</packaging><modules><module>core</module><module>app</module></modules>" +
                "$parentExtra</project>",
        )
        File(root, "core/src/test/java").mkdirs()
        File(root, "core/pom.xml").writeText("<project>$parent<artifactId>$coreArtifact</artifactId></project>")
        File(root, "app").mkdirs()
        File(root, "app/pom.xml")
            .writeText("<project>$parent<artifactId>demo-app</artifactId>$appDependencies</project>")
        return root
    }
}
