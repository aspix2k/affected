package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CargoNextestCliAdapterConformanceTest {

    @Test
    fun `cargo-nextest runs selected packages with default and custom profiles`() = fixture { root ->
        val default = nativePlan(root)
        val custom = nativePlan(root, "ci")
        val defaultResult = executeBatch(
            root,
            cargoCommands(root.path, listOf("affected-alpha:${cargoNextestTask(default)}")),
        )
        val customResult = executeBatch(
            root,
            cargoCommands(root.path, listOf("affected-alpha:${cargoNextestTask(custom)}")),
        )

        assertEquals(CargoNextestMode.PACKAGES, default.mode)
        assertEquals(CargoNextestMode.PACKAGES, custom.mode)
        assertEquals(true, default.failFast)
        assertEquals(false, custom.failFast)
        assertSelectedRun(defaultResult)
        assertSelectedRun(customResult)

        val metadata = execute(root, listOf("cargo", "metadata", "--no-deps", "--format-version", "1"))
        val tasks = CargoMetadata.parse(metadata, root.invariantSeparatorsPath) { hasDoctests ->
            cargoNextestTask(default, hasDoctests)
        }.filter { it.id in setOf("affected-alpha", "affected-gamma", "affected-delta") }
            .map { "${it.executionId}:${it.testTask}" }
        val commands = cargoCommands(root.path, tasks)
        val result = executeBatch(root, commands)

        assertEquals(2, commands.size)
        assertTrue(
            commands.first().arguments.containsAll(
                listOf("affected-alpha", "affected-gamma", "affected-delta"),
            ),
        )
        assertEquals(listOf("-p", "affected-alpha"), commands.last().arguments.takeLast(2))
        assertTrue(result.passed, result.output)
    }

    @Test
    fun `cargo-nextest exposes failures and stops its process tree`() = fixture { root ->
        val source = File(root, "alpha/src/lib.rs")
        val commands = cargoCommands(
            root.path,
            listOf("affected-alpha:${cargoNextestTask(nativePlan(root))}"),
        )
        source.writeText(source.readText().replace("assert_eq!(super::value(), 1)", "assert_eq!(super::value(), 99)"))

        val failed = executeBatch(root, commands)

        assertFalse(failed.passed)
        assertContains(failed.output, "> cargo nextest")
        assertContains(failed.output, "FAIL")
        assertFalse(failed.output.contains("Doc-tests"), failed.output)
        val marker = File(root, "nextest.pid")
        source.writeText(SLEEPING_TEST)
        assertBatchStops(root, commands, marker)
    }

    @Test
    fun `cargo-nextest non fail fast profile still runs doctests after a unit failure`() = fixture { root ->
        val source = File(root, "alpha/src/lib.rs")
        val commands = cargoCommands(
            root.path,
            listOf("affected-alpha:${cargoNextestTask(nativePlan(root, "ci"))}"),
        )
        source.writeText(source.readText().replace("assert_eq!(super::value(), 1)", "assert_eq!(super::value(), 99)"))

        val result = executeBatch(root, commands)

        assertFalse(result.passed)
        assertContains(result.output, "> cargo nextest")
        assertContains(result.output, "> cargo test --doc")
        assertContains(result.output, "Doc-tests affected_alpha")
    }

    @Test
    fun `cargo-nextest non fail fast profile runs every selected doctest package`() = fixture { root ->
        val source = File(root, "alpha/src/lib.rs")
        source.writeText(source.readText().replace("affected_alpha::value(), 1", "affected_alpha::value(), 99"))
        val plan = nativePlan(root, "ci")
        val result = executeBatch(
            root,
            cargoCommands(
                root.path,
                listOf(
                    "affected-alpha:${cargoNextestTask(plan)}",
                    "affected-beta:${cargoNextestTask(plan)}",
                ),
            ),
        )

        assertFalse(result.passed)
        assertContains(result.output, "Doc-tests affected_alpha")
        assertContains(result.output, "Doc-tests affected_beta")
    }

    private fun assertSelectedRun(result: CommandResult) {
        assertTrue(result.passed, result.output)
        assertContains(result.output, "> cargo nextest")
        assertContains(result.output, "> cargo test --doc")
        assertFalse(result.output.contains("\n> cargo test\n"), result.output)
        assertContains(result.output, "affected-alpha")
        assertFalse(result.output.contains("affected-beta"))
        assertContains(result.output, "Doc-tests affected_alpha")
    }

    private fun nativePlan(root: File, profile: String? = null): CargoNextestPlan {
        val config = requireNotNull(cargoNextestValidationSnapshot(root, profile))
        val environment = mapOf("CARGO" to "cargo")
        val version = execute(root, listOf("cargo-nextest", "--version"), environment)
        val configuration = execute(
            root,
            listOf(
                "cargo-nextest", "nextest", "show-config", "version",
                "--manifest-path", File(root, "Cargo.toml").path,
                "--config-file", config.path,
            ),
            environment,
        )
        return detectCargoNextest(root, version, configuration, requestedProfile = profile)
    }

    private fun fixture(block: (File) -> Unit) {
        assumeTrue(System.getProperty(CONFORMANCE_PROPERTY) == "true")
        val source = fixtureRoot()
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        val target = createTempDirectory("affected-cli-cargo").toFile()
        try {
            assertTrue(source.copyRecursively(target, overwrite = true), "Could not copy $source")
            block(target)
        } finally {
            target.deleteRecursively()
        }
    }

    private fun fixtureRoot(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "conformance/cli-fixtures/cargo") }
        .firstOrNull(File::isDirectory)
        ?: File(System.getProperty("user.dir"), "conformance/cli-fixtures/cargo")

    private fun execute(
        directory: File,
        arguments: List<String>,
        environmentOverrides: Map<String, String> = emptyMap(),
    ): String {
        val output = File.createTempFile("affected-cli-output", ".log")
        try {
            val process = ProcessBuilder(arguments)
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .apply { environment().putAll(environmentOverrides) }
                .start()
            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            val text = output.readText()
            assertTrue(completed, "Timed out: ${arguments.joinToString(" ")}\n$text")
            assertEquals(0, process.exitValue(), "Failed: ${arguments.joinToString(" ")}\n$text")
            return text
        } finally {
            output.delete()
        }
    }

    private fun executeBatch(directory: File, commands: List<CliCommand>): CommandResult {
        val output = StringBuilder()
        val handler = SequentialProcessHandler(directory, commands)
        handler.addProcessListener(outputListener(output))
        handler.startNotify()
        val completed = handler.waitFor(TimeUnit.SECONDS.toMillis(COMMAND_TIMEOUT_SECONDS))
        if (!completed) handler.destroyProcess()
        return CommandResult(completed && handler.exitCode == 0, output.toString())
    }

    private fun assertBatchStops(directory: File, commands: List<CliCommand>, marker: File) {
        val output = StringBuilder()
        val instrumented = commands.mapIndexed { index, command ->
            if (index == 0) command.copy(environment = mapOf("AFFECTED_NEXTEST_PID" to marker.path)) else command
        }
        val handler = SequentialProcessHandler(directory, instrumented)
        handler.addProcessListener(outputListener(output))
        handler.startNotify()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (!marker.isFile && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue(marker.isFile, "sleeping nextest process did not start: $output")
        val pid = marker.readText().trim().toLong()
        handler.destroyProcess()
        assertTrue(handler.waitFor(10_000), "nextest handler did not stop")
        assertTrue(handler.exitCode != 0)
        val processDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (processAlive(pid) && System.nanoTime() < processDeadline) Thread.sleep(25)
        assertFalse(processAlive(pid), "nextest child $pid is still alive")
        assertFalse(output.contains("Doc-tests"), output.toString())
    }

    private fun processAlive(pid: Long): Boolean = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)

    private fun outputListener(output: StringBuilder) = object : com.intellij.execution.process.ProcessListener {
        override fun onTextAvailable(
            event: com.intellij.execution.process.ProcessEvent,
            outputType: com.intellij.openapi.util.Key<*>,
        ) {
            output.append(event.text)
        }
    }

    private data class CommandResult(val passed: Boolean, val output: String)

    private companion object {
        const val CONFORMANCE_PROPERTY = "affected.cliConformance"
        const val COMMAND_TIMEOUT_SECONDS = 180L
        val SLEEPING_TEST = """
            pub fn value() -> u32 { 1 }

            #[cfg(test)]
            mod tests {
                #[test]
                fn waits() {
                    std::fs::write(
                        std::env::var("AFFECTED_NEXTEST_PID").unwrap(),
                        std::process::id().to_string(),
                    ).unwrap();
                    std::thread::sleep(std::time::Duration::from_secs(60));
                }
            }
        """.trimIndent()
    }
}
