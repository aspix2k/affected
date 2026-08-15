package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CliRConformanceTest {

    @Test
    fun `r runs the project test command for the affected root`() = fixture("r") { root ->
        val module = rRootModule(root)
        val source = File(root, "R/alpha.R")
        val changes = BuildChanges(listOf(source.path), setOf(source.path), comparedToBase = true)
        val command = rCommands(root, listOf("${module.executionId}:${module.testTask}"), changes).single()
        assertEquals(
            listOf("Rscript", "-e", "testthat::test_local(\".\")"),
            command.arguments,
        )
        val text = execute(root, command)
        assertContains(text, "Starting 2 test processes")
        assertTrue(testMarker(root, "alpha").isFile)
        assertTrue(testMarker(root, "beta").isFile)
        assertTrue(testMarker(root, "unrelated").isFile)
    }

    @Test
    fun `r full suite loads setup and teardown in serial mode`() = fixture("r") { root ->
        val description = File(root, "DESCRIPTION")
        val original = description.readText()
        assertContains(original, "Config/testthat/parallel: true")
        description.writeText(
            original.replace(
                "Config/testthat/parallel: true",
                "Config/testthat/parallel: false",
            ),
        )
        val command = rCommands(root, listOf(".:test")).single()

        val text = execute(root, command)
        assertContains(text, "AlphaTest")
        assertContains(text, "BetaTest")
        assertContains(text, "UnrelatedTest")
        assertEquals(1, "SetupRun".toRegex().findAll(text).count(), text)
        assertEquals(1, "TeardownRun".toRegex().findAll(text).count(), text)
    }

    @Test
    fun `r runs directly changed testthat files in one process`() = fixture("r") { root ->
        val module = rRootModule(root)
        val alpha = File(root, "tests/testthat/test-alpha.R")
        val beta = File(root, "tests/testthat/test-beta.R")
        val changes = BuildChanges(
            files = listOf(beta.path, alpha.path),
            exactSelectionEligible = setOf(alpha.path, beta.path),
            comparedToBase = true,
        )
        val command = rCommands(root, listOf("${module.executionId}:${module.testTask}"), changes).single()
        assertEquals(
            listOf(
                "Rscript",
                "-e",
                "local({version <- utils::packageVersion(\"testthat\"); " +
                    "if (version < \"3.0.0\" || version >= \"4.0.0\") " +
                    "testthat::test_dir(\"tests/testthat\") else {paths <- commandArgs(trailingOnly = TRUE); " +
                    "Sys.setenv(TESTTHAT_PARALLEL = \"false\"); " +
                    "contexts <- sub(\"\\\\.[rR]$\", \"\", " +
                    "sub(\"^test[-_.]?\", \"\", basename(paths))); testthat::test_local(\".\", " +
                    "filter = paste0(\"^(\", paste(contexts, collapse = \"|\"), \")$\"))}})",
                "--args",
                "tests/testthat/test-alpha.R",
                "tests/testthat/test-beta.R",
            ),
            command.arguments,
        )

        val text = execute(root, command)
        assertContains(text, "AlphaTest")
        assertContains(text, "BetaTest")
        assertFalse(text.contains("UnrelatedTest"), text)
        assertFalse(text.contains("Starting 2 test processes"), text)
        assertTrue(testMarker(root, "alpha").isFile)
        assertTrue(testMarker(root, "beta").isFile)
        assertFalse(testMarker(root, "unrelated").exists())
        assertEquals(1, "SetupRun".toRegex().findAll(text).count(), text)
        assertEquals(1, "TeardownRun".toRegex().findAll(text).count(), text)
    }

    @Test
    fun `r reports failure after every selected testthat file ran`() = fixture("r") { root ->
        val alpha = File(root, "tests/testthat/test-alpha.R").apply {
            writeText(
                """
                    test_that("alpha", {
                      cat("AlphaTest\n")
                      expect_true(FALSE)
                    })
                """.trimIndent(),
            )
        }
        val beta = File(root, "tests/testthat/test-beta.R")
        val paths = listOf(alpha.path, beta.path)
        val command = rCommands(
            root,
            listOf(".:test"),
            BuildChanges(paths, paths.toSet(), comparedToBase = true),
        ).single()

        val text = execute(root, command, succeeds = false)
        assertContains(text, "AlphaTest")
        assertContains(text, "BetaTest")
    }

    @Test
    fun `r checks a source package outside the repository and removes output`() = fixture("r-check") { root ->
        val temporary = createTempDirectory("affected-r-check-output").toFile()
        val marker = File.createTempFile("affected-r-check-marker", ".txt").apply { delete() }
        val sentinel = File.createTempFile("affected-r-check-sentinel", ".txt").apply { delete() }
        try {
            val output = Files.createTempDirectory(temporary.toPath(), "affected-r-check-")
            val command = rPackageCheckCommand(output).copy(
                environment = mapOf(
                    "AFFECTED_R_CHECK_MARKER" to marker.path,
                    "AFFECTED_R_SHELL_SENTINEL" to sentinel.path,
                ),
            )

            val text = execute(root, command)
            assertTrue(marker.readText().contains("RPackageCheck"))
            assertTrue(marker.readText().contains("RPackageExample"))
            assertFalse(sentinel.exists(), text)
            assertFalse(root.walkTopDown().any { it.name.endsWith(".Rcheck") })
            assertTrue(temporary.listFiles().orEmpty().isEmpty())
        } finally {
            marker.delete()
            sentinel.delete()
            temporary.deleteRecursively()
        }
    }

    @Test
    fun `r package check propagates test failure and removes output`() = fixture("r-check") { root ->
        val temporary = createTempDirectory("affected-r-check-output").toFile()
        File(root, "tests/check.R").appendText("\nstop(\"AffectedCheckFailure\")\n")
        val marker = File.createTempFile("affected-r-check-marker", ".txt").apply { delete() }
        try {
            val output = Files.createTempDirectory(temporary.toPath(), "affected-r-check-")
            val command = rPackageCheckCommand(output).copy(
                environment = mapOf(
                    "AFFECTED_R_CHECK_MARKER" to marker.path,
                ),
            )

            val text = execute(root, command, succeeds = false)
            assertContains(text, "AffectedCheckFailure")
            assertTrue(marker.readText().contains("RPackageCheck"))
            assertTrue(marker.readText().contains("RPackageExample"))
            assertFalse(root.walkTopDown().any { it.name.endsWith(".Rcheck") })
            assertTrue(temporary.listFiles().orEmpty().isEmpty())
        } finally {
            marker.delete()
            temporary.deleteRecursively()
        }
    }

    @Test
    fun `stopping an R package check terminates children and removes output`() = fixture("r-check") { root ->
        val temporary = createTempDirectory("affected-r-check-output").toFile()
        val marker = File.createTempFile("affected-r-check-cancel", ".txt").apply { delete() }
        File(root, "tests/check.R").appendText(
            "\ncat(\"started\", file = Sys.getenv(\"AFFECTED_R_CANCEL_MARKER\"))\nSys.sleep(60)\n",
        )
        val output = Files.createTempDirectory(temporary.toPath(), "affected-r-check-")
        val command = rPackageCheckCommand(output).copy(
            environment = mapOf("AFFECTED_R_CANCEL_MARKER" to marker.path),
        )
        val handler = SequentialProcessHandler(root, listOf(command))

        try {
            handler.startNotify()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            while (!marker.isFile && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue(marker.isFile, "R package check did not reach the cancellation marker")
            handler.destroyProcess()
            assertTrue(handler.waitFor(30_000), "R package check did not stop")
            assertTrue(handler.exitCode != 0)
            assertTrue(temporary.listFiles().orEmpty().isEmpty())
        } finally {
            if (!handler.isProcessTerminated) handler.destroyProcess()
            marker.delete()
            temporary.deleteRecursively()
        }
    }

    private fun fixture(name: String, block: (File) -> Unit) {
        assumeTrue(System.getProperty("affected.cliConformance") == "true")
        val source = File(fixtureRoot(), name)
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        val container = createTempDirectory("affected-cli-$name").toFile()
        val target = if (name == "r-check") {
            File(container, "package with spaces;touch \$AFFECTED_R_SHELL_SENTINEL").apply { mkdirs() }
        } else {
            container
        }
        try {
            assertTrue(source.copyRecursively(target, overwrite = true), "Could not copy $source")
            block(target)
        } finally {
            container.deleteRecursively()
        }
    }

    private fun fixtureRoot(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "conformance/cli-fixtures") }
        .firstOrNull(File::isDirectory)
        ?: File(System.getProperty("user.dir"), "conformance/cli-fixtures")

    private fun testMarker(root: File, context: String): File =
        File(root, "tests/testthat/$context.marker")

    private fun execute(directory: File, command: CliCommand, succeeds: Boolean = true): String {
        val output = File.createTempFile("affected-cli-output", ".log")
        try {
            val processBuilder = ProcessBuilder(command.arguments)
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(output)
            processBuilder.environment().putAll(command.environment)
            val process = processBuilder.start()
            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            val text = output.readText()
            assertTrue(completed, "Timed out: ${command.arguments.joinToString(" ")}\n$text")
            if (succeeds) {
                assertEquals(0, process.exitValue(), "Unexpected exit: ${command.arguments.joinToString(" ")}\n$text")
            } else {
                assertNotEquals(
                    0,
                    process.exitValue(),
                    "Unexpected success: ${command.arguments.joinToString(" ")}\n$text",
                )
            }
            return text
        } finally {
            output.delete()
        }
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 180L
    }
}
