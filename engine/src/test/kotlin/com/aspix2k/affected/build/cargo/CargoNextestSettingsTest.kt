package com.aspix2k.affected.build.cargo

import com.aspix2k.affected.build.testSnapshotRoot
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class CargoNextestSettingsTest {

    @Test
    fun `settings that change how tests run keep package selection and reach the generated config`() {
        val root = workspace(
            """
            nextest-version = { required = "0.9.85" }

            [profile.default]
            retries = { backoff = "exponential", count = 3, delay = "1s", jitter = true }
            test-threads = "num-cpus"
            leak-timeout = "100ms"

            [profile.ci]
            fail-fast = false
            retries = 2
            slow-timeout = { period = "60s", terminate-after = 2 }
            threads-required = 2
            global-timeout = "10m"
            flaky-result = "fail"
            """.trimIndent(),
        )
        val plan = detectCargoNextest(root, VERSION, CONFIGURATION, requestedProfile = "ci")

        assertEquals(CargoNextestPlan(CargoNextestMode.PACKAGES, "ci", "0.9.143", false), plan)
        assertEquals(
            """
            nextest-version = { required = "0.9.143" }

            [profile.ci]
            fail-fast = false
            retries = 2
            flaky-result = "fail"
            test-threads = "num-cpus"
            threads-required = 2
            slow-timeout = { period = "60s", terminate-after = 2 }
            leak-timeout = "100ms"
            global-timeout = "10m"

            """.trimIndent(),
            requireNotNull(cargoNextestValidationSnapshot(root, testSnapshotRoot, "ci")).readText(),
        )
        assertEquals(
            "retries = { backoff = \"exponential\", count = 3, delay = \"1s\", jitter = true }\n" +
                "test-threads = \"num-cpus\"\nleak-timeout = \"100ms\"\n",
            cargoNextestCarriedSettings(root, "default"),
        )
    }

    @Test
    fun `run settings in a shape the generated config cannot carry retain cargo test`() {
        val settings = listOf(
            "retries = -1",
            "retries = 1.5",
            "retries = [1, 2]",
            "retries = { count = { nested = 1 } }",
            "retries = { \"quoted key\" = 1 }",
            "slow-timeout = \"60s\\\"\\nfail-fast = false\"",
            "leak-timeout = \"\"",
            "run-extra-args = [\"--skip\", \"slow\"]",
        )

        settings.forEach { setting ->
            val config = "nextest-version = { required = '0.9.85' }\n[profile.default]\n$setting"
            assertEquals(
                CargoNextestPlan(CargoNextestMode.CARGO_TEST, null),
                detectCargoNextest(workspace(config), VERSION, configurationOutput = CONFIGURATION),
                setting,
            )
        }
        assertEquals(
            CargoNextestPlan(CargoNextestMode.CARGO_TEST, null),
            detectCargoNextest(
                workspace("nextest-version = { required = '0.9.85' }\n[profile.other]\nretries = -1"),
                VERSION,
                configurationOutput = CONFIGURATION,
            ),
        )
        assertEquals(
            CargoNextestPlan(CargoNextestMode.CARGO_TEST, null),
            detectCargoNextest(
                workspace("nextest-version = { required = '0.9.85' }\n[test-groups.serial]\nmax-threads = 1"),
                VERSION,
                configurationOutput = CONFIGURATION,
            ),
        )
    }

    @Test
    fun `a run reads the run settings again and writes them into its config`() {
        val root = workspace("nextest-version = { required = '0.9.85' }\n[profile.default]\nretries = 2")
        val task = cargoNextestTask(CargoNextestPlan(CargoNextestMode.PACKAGES, "default", "0.9.143", true))

        val arguments = cargoCommands(root.path, listOf("alpha:$task"), snapshotRoot = testSnapshotRoot)
            .first().arguments
        val snapshot = File(arguments[arguments.indexOf("--config-file") + 1])

        assertEquals(true, snapshot.readText().endsWith("fail-fast = true\nretries = 2\n"))
    }

    private fun workspace(config: String): File = createTempDirectory("cargo-nextest-settings").toFile().also { root ->
        File(root, ".config").mkdirs()
        File(root, ".config/nextest.toml").writeText(config)
    }

    private companion object {
        const val VERSION = "cargo-nextest 0.9.143"
        val CONFIGURATION = """
            current nextest version: 0.9.143
            version requirements:
                - required: 0.9.143
            evaluation result: ok
        """.trimIndent()
    }
}
