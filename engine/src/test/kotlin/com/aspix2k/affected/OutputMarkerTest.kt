package com.aspix2k.affected

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutputMarkerTest {

    @Test
    fun `a planned Gradle task is seen across split output and other tasks are not`() {
        val marker = OutputMarker(gradleTaskReached(listOf(":core:test", "--tests", "demo.CoreTest")))

        marker.accept("> Task :core:compileJava\n> Task :core:te")
        assertFalse(marker.seen)
        marker.accept("st FAILED\r\n")
        assertTrue(marker.seen)

        val other = OutputMarker(gradleTaskReached(listOf(":core:test")))
        other.accept("> Task :core:testClasses\n> Task :app:test\nA problem occurred configuring root project\n")
        assertFalse(other.seen)
    }

    @Test
    fun `a Surefire or Failsafe test goal means Maven reached the tests`() {
        assertTrue(mavenTestsReached("[INFO] --- surefire:3.5.4:test (default-test) @ core ---"))
        assertTrue(mavenTestsReached("[INFO] --- maven-surefire-plugin:2.22.2:test (default-test) @ core ---"))
        assertTrue(mavenTestsReached("[INFO] --- failsafe:3.5.4:integration-test (default) @ core ---"))
        assertFalse(mavenTestsReached("[INFO] --- compiler:3.13.0:testCompile (default-testCompile) @ core ---"))
        assertFalse(mavenTestsReached("[ERROR] Failed to execute goal on project core: Could not resolve dependencies"))
    }

    @Test
    fun `output without line ends does not grow without bound`() {
        val marker = OutputMarker { it == "done" }

        repeat(64) { marker.accept("x".repeat(1024)) }
        marker.accept("\ndone\n")

        assertTrue(marker.seen)
    }
}
