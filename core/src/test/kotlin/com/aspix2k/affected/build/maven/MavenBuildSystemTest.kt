package com.aspix2k.affected.build.maven

import org.jdom.Element
import org.jetbrains.idea.maven.model.MavenPlugin
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MavenBuildSystemTest {

    @Test
    fun `Scala and Groovy sources belong to Maven modules`() {
        assertContains(MavenBuildSystem().sourceExtensions, "scala")
        assertContains(MavenBuildSystem().sourceExtensions, "groovy")
    }

    @Test
    fun `a configured test source directory counts as tests`() {
        val module = createTempDirectory("maven-custom-tests").toFile()
        val custom = File(module, "checks").apply { mkdirs() }

        assertFalse(mavenHoldsTests(module.path, emptyList()))
        assertFalse(mavenHoldsTests(module.path, listOf(File(module, "missing").path)))
        assertTrue(mavenHoldsTests(module.path, listOf(custom.path)))
    }

    @Test
    fun `failsafe binding promotes the reactor test goal to verify`() {
        val plugin = MavenPlugin(
            "org.apache.maven.plugins",
            "maven-failsafe-plugin",
            "3.5.4",
            false,
            false,
            Element("configuration"),
            listOf(
                MavenPlugin.Execution(
                    "default",
                    listOf("integration-test", "verify"),
                    Element("configuration"),
                ),
            ),
            emptyList(),
        )

        assertTrue(hasFailsafeIntegrationTests(listOf(plugin)))
        assertEquals(
            setOf("/reactor"),
            mavenFailsafeRoots(
                listOf(
                    "/reactor" to listOf(plugin),
                    "/reactor" to emptyList(),
                    "/other" to emptyList(),
                ),
            ),
        )
        assertEquals("verify", mavenTestGoal(hasFailsafeInReactor = true))
    }

    @Test
    fun `unbound failsafe leaves the reactor test goal unchanged`() {
        val plugin = MavenPlugin(
            "org.apache.maven.plugins",
            "maven-failsafe-plugin",
            "3.5.4",
            false,
            false,
            Element("configuration"),
            listOf(MavenPlugin.Execution("default", listOf("verify"), Element("configuration"))),
            emptyList(),
        )

        assertFalse(hasFailsafeIntegrationTests(listOf(plugin)))
        assertEquals("test", mavenTestGoal(hasFailsafeInReactor = false))
    }
}
