package com.aspix2k.affected.build

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `Gradle stop strategy overrides later IDE continue arguments`() {
        val script = Path.of("/plugin/agent/affected-failure-strategy.init.gradle")

        assertEquals(
            listOf("--init-script", script.toString()),
            gradleInvocationArguments(
                emptyList(),
                stopAfterFirstFailure = true,
                failureStrategyScript = script,
            ),
        )
    }

    @Test
    fun `Gradle stop strategy fails closed without its packaged script`() {
        assertFailsWith<IllegalArgumentException> {
            gradleInvocationArguments(
                emptyList(),
                stopAfterFirstFailure = true,
                failureStrategyScript = null,
            )
        }
    }

    @Test
    fun `Gradle strategy preserves collector arguments`() {
        val collector = listOf("--init-script", "/tmp/affected.gradle")

        assertEquals(
            collector + "--continue",
            gradleInvocationArguments(collector, stopAfterFirstFailure = false),
        )
        assertEquals(
            collector + listOf("--init-script", "/plugin/agent/affected-failure-strategy.init.gradle"),
            gradleInvocationArguments(
                collector,
                stopAfterFirstFailure = true,
                failureStrategyScript = Path.of("/plugin/agent/affected-failure-strategy.init.gradle"),
            ),
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
