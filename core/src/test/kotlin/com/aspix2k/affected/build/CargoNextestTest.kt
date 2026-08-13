package com.aspix2k.affected.build

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class CargoNextestTest {

    @Test
    fun `a repository-owned default profile enables package selection`() {
        val root = workspace(
            """
            nextest-version = { required = "0.9.85" }

            [profile.default]
            fail-fast = false
            """.trimIndent(),
        )

        assertEquals(
            CargoNextestPlan(CargoNextestMode.PACKAGES, "default", "0.9.143", false),
            detectCargoNextest(root, VERSION, configurationOutput = CONFIGURATION),
        )
    }

    @Test
    fun `a bounded custom profile keeps package selection`() {
        val root = workspace(
            """
            nextest-version = { required = "0.9.85" }

            [profile.default]
            fail-fast = false

            [profile.ci]
            fail-fast = false
            """.trimIndent(),
        )

        assertEquals(
            CargoNextestPlan(CargoNextestMode.PACKAGES, "ci", "0.9.143", false),
            detectCargoNextest(root, VERSION, configurationOutput = CONFIGURATION, requestedProfile = "ci"),
        )
    }

    @Test
    fun `profiles preserve nextest fail fast defaults`() {
        val builtIn = workspace("nextest-version = { required = '0.9.85' }")
        val inherited = workspace(
            """
            nextest-version = { required = "0.9.85" }

            [profile.default]
            fail-fast = false

            [profile.ci]
            """.trimIndent(),
        )

        assertEquals(
            CargoNextestPlan(CargoNextestMode.PACKAGES, "default", "0.9.143", true),
            detectCargoNextest(builtIn, VERSION, configurationOutput = CONFIGURATION),
        )
        assertEquals(
            CargoNextestPlan(CargoNextestMode.PACKAGES, "ci", "0.9.143", false),
            detectCargoNextest(inherited, VERSION, configurationOutput = CONFIGURATION, requestedProfile = "ci"),
        )
    }

    @Test
    fun `missing repository config retains cargo test`() {
        val root = createTempDirectory("cargo-nextest-missing").toFile()

        assertEquals(
            CargoNextestPlan(CargoNextestMode.CARGO_TEST, null),
            detectCargoNextest(root, VERSION, configurationOutput = CONFIGURATION),
        )
    }

    @Test
    fun `unsupported nextest version retains cargo test`() {
        val root = workspace("nextest-version = { required = '0.9.85' }")

        listOf("cargo-nextest 0.9.142", "cargo-nextest 0.10.0", "cargo-nextest 0.9.143-beta.1").forEach {
            assertEquals(
                CargoNextestPlan(CargoNextestMode.CARGO_TEST, null),
                detectCargoNextest(root, it, configurationOutput = CONFIGURATION),
            )
        }
    }

    @Test
    fun `official multiline version output is accepted`() {
        val root = workspace("nextest-version = { required = '0.9.85' }")
        val output = """
            cargo-nextest 0.9.143 (60fa45f63 2026-08-04)
            release: 0.9.143
            commit-hash: 60fa45f638ffc3f35e74afa65737f45fcd32db2a
            commit-date: 2026-08-04
            host: aarch64-apple-darwin
        """.trimIndent()

        assertEquals(
            CargoNextestPlan(CargoNextestMode.PACKAGES, "default", "0.9.143", true),
            detectCargoNextest(root, output, configurationOutput = CONFIGURATION),
        )
    }

    @Test
    fun `unsupported nextest settings retain cargo test`() {
        val configs = listOf(
            "nextest-version = { required = '0.9.85' }\n[script.setup]\ncommand = 'prepare'",
            "nextest-version = { required = '0.9.85' }\n[[profile.default.overrides]]\nfilter = 'test(all())'",
            "nextest-version = { required = '0.9.85' }\n[profile.default]\ndefault-filter = 'test(all())'",
        )

        configs.forEach { config ->
            assertEquals(
                CargoNextestPlan(CargoNextestMode.CARGO_TEST, null),
                detectCargoNextest(workspace(config), VERSION, configurationOutput = CONFIGURATION),
            )
        }
        assertEquals(
            CargoNextestPlan(CargoNextestMode.CARGO_TEST, null),
            detectCargoNextest(
                workspace("nextest-version = { required = '0.9.85' }"),
                VERSION,
                configurationOutput = null,
            ),
        )
    }

    @Test
    fun `custom Cargo runners retain cargo test and build metadata widens to the workspace`() {
        val runner = workspace("nextest-version = { required = '0.9.85' }")
        val plan = CargoNextestPlan(CargoNextestMode.PACKAGES, "default", "0.9.143", false)

        assertEquals(
            CargoNextestPlan(CargoNextestMode.CARGO_TEST, null),
            detectCargoNextest(runner, VERSION, configurationOutput = CONFIGURATION, cargoConfigurationPresent = true),
        )
        assertEquals(
            plan.copy(mode = CargoNextestMode.WORKSPACE),
            conservativeCargoNextest(plan, hasCustomBuild = true),
        )
        assertEquals(plan.copy(mode = CargoNextestMode.WORKSPACE), conservativeCargoNextest(plan, null))
    }

    @Test
    fun `Cargo config in an ancestor is detected conservatively`() {
        val parent = createTempDirectory("cargo-config").toFile()
        val root = File(parent, "workspace").apply { mkdirs() }
        val cargoHome = File(parent, "cargo-home").apply { mkdirs() }
        File(parent, ".cargo").mkdirs()
        File(parent, ".cargo/config.toml").writeText("[build]\nrustc-wrapper = 'wrapper'")

        assertEquals(true, cargoConfigurationExists(root, mapOf("CARGO_HOME" to cargoHome.path)))
    }

    @Test
    fun `Cargo runner environment is detected independently`() {
        val parent = createTempDirectory("cargo-environment").toFile()
        val root = File(parent, "workspace").apply { mkdirs() }
        val cargoHome = File(parent, "cargo-home").apply { mkdirs() }
        val isolated = mapOf("CARGO_HOME" to cargoHome.path)

        assertEquals(false, cargoConfigurationExists(root, isolated))
        assertEquals(true, cargoConfigurationExists(root, mapOf("CARGO_HOME" to "relative-cargo-home")))
        assertEquals(true, cargoConfigurationExists(root, mapOf("HOME" to File(parent, "other-home").path)))
        assertEquals(true, cargoConfigurationExists(root, isolated + ("CARGO_ALIAS_NEXTEST" to "run --all")))
        assertEquals(
            true,
            cargoConfigurationExists(root, isolated + ("CARGO_BUILD_RUSTC_WORKSPACE_WRAPPER" to "wrapper")),
        )
        assertEquals(
            true,
            cargoConfigurationExists(
                root,
                isolated + ("CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_RUNNER" to "wrapper"),
            ),
        )
        assertEquals(
            System.getProperty("os.name").startsWith("Windows"),
            cargoConfigurationExists(root, isolated + ("cargo_alias_nextest" to "run --all")),
        )
    }

    @Test
    fun `nextest executable identity changes when the installed binary changes`() {
        val directory = createTempDirectory("cargo-nextest-bin").toFile()
        val executable =
            File(
                directory,
                if (System.getProperty("os.name").startsWith("Windows")) "cargo-nextest.exe" else "cargo-nextest",
            )
        executable.writeText("first")
        executable.setExecutable(true)
        val environment = mapOf("PATH" to directory.path)
        val first = cargoNextestExecutableStamp(environment)

        executable.appendText("-second")

        assertNotEquals(null, first)
        assertNotEquals(first, cargoNextestExecutableStamp(environment))
        assertNull(cargoNextestExecutableStamp(mapOf("PATH" to ".")))
    }

    @Test
    fun `nextest executable identity includes content when size and time stay unchanged`() {
        val directory = createTempDirectory("cargo-nextest-content").toFile()
        val executable = File(directory, if (isWindows()) "cargo-nextest.exe" else "cargo-nextest")
        executable.writeText("first")
        executable.setExecutable(true)
        val modified = Files.getLastModifiedTime(executable.toPath())
        val environment = mapOf("PATH" to directory.path)
        val before = cargoNextestExecutableIdentity(environment)

        executable.writeText("other")
        Files.setLastModifiedTime(executable.toPath(), modified)

        assertNotEquals(null, before)
        assertNotEquals(before, cargoNextestExecutableIdentity(environment))
    }

    @Test
    fun `planned nextest execution falls back when the executable identity changes`() {
        val root = createTempDirectory("cargo-nextest-runtime").toFile()
        val cargoHome = File(root, "cargo-home").apply { mkdirs() }
        val bin = File(root, "bin").apply { mkdirs() }
        val executable = File(bin, if (isWindows()) "cargo-nextest.exe" else "cargo-nextest")
        executable.writeText("first")
        executable.setExecutable(true)
        cargoExecutable(bin)
        val environment = mapOf("PATH" to bin.path, "CARGO_HOME" to cargoHome.path)
        val identity = requireNotNull(cargoNextestExecutableIdentity(environment))
        val task = cargoNextestTask(
            CargoNextestPlan(CargoNextestMode.PACKAGES, "default", "0.9.143", true, identity),
        )

        assertEquals("cargo nextest", cargoCommandsForRun(root.path, listOf("alpha:$task"), environment).first().title)
        executable.appendText("-changed")
        assertEquals(
            listOf("cargo", "test", "--workspace"),
            cargoCommandsForRun(root.path, listOf("alpha:$task"), environment).single().arguments,
        )
    }

    @Test
    fun `planned nextest execution invokes the verified executable directly`() {
        val root = createTempDirectory("cargo-nextest-provenance").toFile()
        val cargoHome = File(root, "cargo-home").apply { mkdirs() }
        val cargoHomeBin = File(cargoHome, "bin").apply { mkdirs() }
        executable(cargoHomeBin, "unverified")
        val pathBin = File(root, "path-bin").apply { mkdirs() }
        val verified = executable(pathBin, "verified")
        val cargo = cargoExecutable(pathBin)
        val environment = mapOf("PATH" to pathBin.path, "CARGO_HOME" to cargoHome.path)
        val identity = requireNotNull(cargoNextestExecutableIdentity(environment))
        val task = cargoNextestTask(
            CargoNextestPlan(CargoNextestMode.PACKAGES, "default", "0.9.143", true, identity),
        )

        val command = cargoCommandsForRun(root.path, listOf("alpha:$task"), environment).first()

        assertEquals(listOf(verified.canonicalPath, "nextest"), command.arguments.take(2))
        assertEquals(cargo.absolutePath, command.environment["CARGO"])
    }

    @Test
    fun `nextest executable identity follows a bounded installation symlink`() {
        if (System.getProperty("os.name").startsWith("Windows")) return
        val directory = createTempDirectory("cargo-nextest-link").toFile()
        val first = File(directory, "first").apply { writeText("first"); setExecutable(true) }
        val second = File(directory, "second").apply { writeText("second"); setExecutable(true) }
        val executable = File(directory, "cargo-nextest").toPath()
        java.nio.file.Files.createSymbolicLink(executable, first.toPath())
        val environment = mapOf("PATH" to directory.path)
        val before = cargoNextestExecutableStamp(environment)

        java.nio.file.Files.delete(executable)
        java.nio.file.Files.createSymbolicLink(executable, second.toPath())

        assertNotEquals(null, before)
        assertNotEquals(before, cargoNextestExecutableStamp(environment))
    }

    @Test
    fun `generated config preserves only the selected bounded profile`() {
        val root = workspace(
            """
            nextest-version = { required = "0.9.85" }

            [profile.default]
            fail-fast = false

            [profile.strict]
            fail-fast = true
            """.trimIndent(),
        )
        val plan = detectCargoNextest(
            root,
            VERSION,
            CONFIGURATION,
            requestedProfile = "strict",
        )

        val snapshot = requireNotNull(cargoNextestSnapshot(cargoNextestTask(plan)))

        assertEquals(
            """
            nextest-version = { required = "0.9.143" }

            [profile.strict]
            fail-fast = true

            """.trimIndent(),
            snapshot.readText(),
        )
    }

    @Test
    fun `tampered generated config fails closed`() {
        val task = cargoNextestTask("tampered")
        val snapshot = requireNotNull(cargoNextestSnapshot(task))

        try {
            snapshot.writeText("x".repeat(64 * 1024 + 1))

            assertNull(cargoNextestSnapshot(task))
        } finally {
            snapshot.delete()
        }
    }

    @Test
    fun `nextest environment overrides retain cargo test`() {
        assertEquals(true, unsupportedNextestEnvironment(mapOf("CARGO" to "/tmp/cargo-wrapper")))
        assertEquals(true, unsupportedNextestEnvironment(mapOf("NEXTEST_RETRIES" to "2")))
        assertEquals(true, unsupportedNextestEnvironment(mapOf("NEXTEST_FLAKY_RESULT" to "pass")))
        assertEquals(false, unsupportedNextestEnvironment(mapOf("NEXTEST_PROFILE" to "ci")))
        if (System.getProperty("os.name").startsWith("Windows")) {
            assertEquals(true, unsupportedNextestEnvironment(mapOf("nextest_retries" to "2")))
            assertEquals("ci", cargoNextestProfile(mapOf("nextest_profile" to "ci")))
            assertEquals(true, unsupportedNextestEnvironment(mapOf("PATH" to "C:\\bin", "path" to "C:\\bin")))
        } else {
            assertEquals(false, unsupportedNextestEnvironment(mapOf("nextest_retries" to "2")))
            assertNull(cargoNextestProfile(mapOf("nextest_profile" to "ci")))
        }
    }

    @Test
    fun `direct nextest execution rejects a custom Cargo executable`() {
        val root = createTempDirectory("cargo-nextest-cargo-env").toFile()
        val cargoHome = File(root, "cargo-home").apply { mkdirs() }
        val bin = File(root, "bin").apply { mkdirs() }
        val nextest = executable(bin, "verified")
        val environment = mapOf(
            "PATH" to bin.path,
            "CARGO_HOME" to cargoHome.path,
            "CARGO" to File(root, "cargo-wrapper").path,
        )
        val task = cargoNextestTask(
            CargoNextestPlan(
                CargoNextestMode.PACKAGES,
                "default",
                "0.9.143",
                true,
                requireNotNull(cargoNextestExecutableIdentity(nextest.toPath())),
            ),
        )

        assertEquals(
            listOf("cargo", "test", "--workspace"),
            cargoCommandsForRun(root.path, listOf("alpha:$task"), environment).single().arguments,
        )
    }

    @Test
    fun `missing version malformed config and unknown profile retain cargo test`() {
        val configs = listOf(
            "[profile.default]\nfail-fast = false",
            "nextest-version = { required = '0.9.85' }\nprofile = 'invalid'",
            "nextest-version = { required = '0.9.85'",
            "nextest-version = { required = '0.9.84' }",
            "nextest-version = { required = '0.9.144' }",
        )

        configs.forEach { config ->
            assertEquals(
                CargoNextestPlan(CargoNextestMode.CARGO_TEST, null),
                detectCargoNextest(workspace(config), VERSION, configurationOutput = CONFIGURATION),
            )
        }
        assertEquals(
            CargoNextestPlan(CargoNextestMode.CARGO_TEST, null),
            detectCargoNextest(
                workspace("nextest-version = { required = '0.9.85' }"),
                VERSION,
                configurationOutput = CONFIGURATION,
                requestedProfile = "release",
            ),
        )
        assertEquals(
            CargoNextestPlan(CargoNextestMode.CARGO_TEST, null),
            detectCargoNextest(
                workspace(
                    """
                    nextest-version = { required = '0.9.85' }

                    [profile.default-ci]
                    fail-fast = false
                    """.trimIndent(),
                ),
                VERSION,
                configurationOutput = CONFIGURATION,
                requestedProfile = "default-ci",
            ),
        )
    }

    @Test
    fun `missing or malformed show config output retains cargo test`() {
        val root = workspace("nextest-version = { required = '0.9.85' }")

        listOf(null, "", "evaluation result: ok", CONFIGURATION.replace("0.9.143", "0.9.142")).forEach { output ->
            assertEquals(
                CargoNextestPlan(CargoNextestMode.CARGO_TEST, null),
                detectCargoNextest(root, VERSION, configurationOutput = output),
            )
        }
    }

    @Test
    fun `symlinked and oversized configs retain cargo test`() {
        val symlinkRoot = createTempDirectory("cargo-nextest-symlink").toFile()
        val external = File.createTempFile("nextest", ".toml").apply {
            writeText("nextest-version = { required = '0.9.85' }")
        }
        File(symlinkRoot, ".config").mkdirs()
        java.nio.file.Files.createSymbolicLink(File(symlinkRoot, ".config/nextest.toml").toPath(), external.toPath())
        val oversized = workspace("x".repeat(64 * 1024 + 1))

        listOf(symlinkRoot, oversized).forEach { root ->
            assertEquals(
                CargoNextestPlan(CargoNextestMode.CARGO_TEST, null),
                detectCargoNextest(root, VERSION, configurationOutput = CONFIGURATION),
            )
        }
    }

    private fun workspace(config: String): File = createTempDirectory("cargo-nextest").toFile().also { root ->
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

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

    private fun executable(directory: File, content: String): File =
        File(directory, if (isWindows()) "cargo-nextest.exe" else "cargo-nextest").apply {
            writeText(content)
            setExecutable(true)
        }

    private fun cargoExecutable(directory: File): File =
        File(directory, if (isWindows()) "cargo.exe" else "cargo").apply {
            writeText("cargo")
            setExecutable(true)
        }
}
