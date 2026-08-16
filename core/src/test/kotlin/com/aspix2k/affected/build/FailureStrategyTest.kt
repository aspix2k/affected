package com.aspix2k.affected.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailureStrategyTest {

    @Test
    fun `the full plan continues after a failure`() {
        assertTrue(continuesAfterFailure(stopAfterFirstFailure = false))
    }

    @Test
    fun `stop after the first failure does not continue`() {
        assertFalse(continuesAfterFailure(stopAfterFirstFailure = true))
    }

    @Test
    fun `Gradle full plan continues after failures`() {
        assertEquals(
            listOf("--continue"),
            gradleInvocationArguments(emptyList(), stopAfterFirstFailure = false),
        )
    }

    @Test
    fun `Gradle stop strategy keeps the native default`() {
        assertEquals(
            emptyList(),
            gradleInvocationArguments(emptyList(), stopAfterFirstFailure = true),
        )
    }

    @Test
    fun `Gradle strategy preserves collector arguments`() {
        val collector = listOf("--init-script", "/tmp/affected.gradle")

        assertEquals(
            collector + "--continue",
            gradleInvocationArguments(collector, stopAfterFirstFailure = false),
        )
        assertEquals(
            collector,
            gradleInvocationArguments(collector, stopAfterFirstFailure = true),
        )
    }

    @Test
    fun `Gradle strategy carries static selection diagnostics into the owned invocation`() {
        val collector = listOf("--init-script", "/tmp/affected.gradle")
        val selection = GradleTaskSelection(
            listOf(":shared:iosSimulatorArm64Test"),
            listOf(GradleSelectionReason.KOTLIN_NATIVE_EXACT_UNSUPPORTED),
        )

        assertEquals(
            collector +
                "-Daffected.selection.reasons=KOTLIN_NATIVE_EXACT_UNSUPPORTED" +
                "--continue",
            gradleInvocationArguments(collector, selection, stopAfterFirstFailure = false),
        )
    }

    @Test
    fun `Maven full plan fails at the end`() {
        assertEquals(
            listOf("--fail-at-end"),
            mavenInvocationArguments(emptyList(), stopAfterFirstFailure = false),
        )
    }

    @Test
    fun `Maven stop strategy fails fast`() {
        assertEquals(
            listOf("--fail-fast"),
            mavenInvocationArguments(emptyList(), stopAfterFirstFailure = true),
        )
    }

    @Test
    fun `Maven strategy preserves collector arguments`() {
        val collector = listOf("-Daffected.collector=/tmp/collector.jar", "-Daffected.session=42")

        assertEquals(
            collector + "--fail-at-end",
            mavenInvocationArguments(collector, stopAfterFirstFailure = false),
        )
        assertEquals(
            collector + "--fail-fast",
            mavenInvocationArguments(collector, stopAfterFirstFailure = true),
        )
    }
}
