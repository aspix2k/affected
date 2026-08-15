package com.aspix2k.affected.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CargoFailureStrategyTest {

    @Test
    fun `nextest obeys the global strategy instead of the repository profile`() {
        val strictTask = cargoNextestTask(
            CargoNextestPlan(CargoNextestMode.PACKAGES, "default", "0.9.143", true),
        )
        val full = cargoCommands(
            "/workspace",
            listOf("core:$strictTask"),
            stopAfterFirstFailure = false,
        )
        val fullSnapshot = File(full.first().arguments[full.first().arguments.indexOf("--config-file") + 1])

        assertContains(fullSnapshot.readText(), "fail-fast = false")
        assertTrue(full.first().continueOnFailure)
        assertEquals(mapOf("CARGO" to "cargo"), full.first().environment)
        assertContains(full.last().arguments, "--no-fail-fast")
        assertEquals(listOf("-p", "core"), full.first().arguments.takeLast(2))

        val relaxedTask = cargoNextestTask(
            CargoNextestPlan(CargoNextestMode.PACKAGES, "ci", "0.9.143", false),
        )
        val stop = cargoCommands(
            "/workspace",
            listOf("core:$relaxedTask"),
            stopAfterFirstFailure = true,
        )
        val stopSnapshot = File(stop.first().arguments[stop.first().arguments.indexOf("--config-file") + 1])

        assertContains(stopSnapshot.readText(), "fail-fast = true")
        assertFalse(stop.first().continueOnFailure)
        assertEquals(mapOf("CARGO" to "cargo"), stop.first().environment)
        assertFalse(stop.last().arguments.contains("--no-fail-fast"))
        assertEquals(listOf("-p", "core"), stop.first().arguments.takeLast(2))
    }

    @Test
    fun `cargo test fallback obeys the global strategy`() {
        assertEquals(
            listOf("cargo", "test", "--no-fail-fast", "-p", "core"),
            cargoCommands(
                "/workspace",
                listOf("core:test"),
                stopAfterFirstFailure = false,
            ).single().arguments,
        )
        assertEquals(
            listOf("cargo", "test", "-p", "core"),
            cargoCommands(
                "/workspace",
                listOf("core:test"),
                stopAfterFirstFailure = true,
            ).single().arguments,
        )

        val nextest = cargoNextestTask("default")
        assertEquals(
            listOf("cargo", "test", "--no-fail-fast", "--workspace"),
            cargoCommands(
                "/workspace",
                listOf("core:$nextest"),
                unsafeCargoExecution = true,
                stopAfterFirstFailure = false,
            ).single().arguments,
        )
        assertEquals(
            listOf("cargo", "test", "--workspace"),
            cargoCommands(
                "/workspace",
                listOf("core:$nextest"),
                unsafeCargoExecution = true,
                stopAfterFirstFailure = true,
            ).single().arguments,
        )
    }
}
