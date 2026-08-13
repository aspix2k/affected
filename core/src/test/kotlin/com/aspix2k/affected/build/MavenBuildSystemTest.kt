package com.aspix2k.affected.build

import org.jdom.Element
import org.jetbrains.idea.maven.model.MavenPlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MavenBuildSystemTest {

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
