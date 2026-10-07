package com.aspix2k.affected.build.maven

import com.aspix2k.affected.build.BuildChanges
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MavenProjectRulesTest {

    @Test
    fun `a changed pom or Maven configuration widens to the whole reactor and a source does not`() {
        fun widens(vararg files: String) =
            mavenRequiresWorkspace("/repo", BuildChanges(files.asList(), emptySet(), comparedToBase = true))

        assertTrue(widens("/repo/pom.xml"))
        assertTrue(widens("/repo/core/pom.xml"))
        assertTrue(widens("/repo/.mvn/maven.config"))
        assertFalse(widens("/repo/core/src/main/java/A.java"))
        assertFalse(widens("/repo/core/src/main/resources/pom.xml.template"))
        assertFalse(widens("/other/pom.xml"))
    }
}
