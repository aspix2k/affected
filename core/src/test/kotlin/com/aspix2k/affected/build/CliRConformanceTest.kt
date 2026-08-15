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
        val text = execute(root, command.arguments)
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
                    "contexts <- sub(\"\\\\.[rR]$\", \"\", " +
                    "sub(\"^test[-_.]?\", \"\", basename(paths))); testthat::test_local(\".\", " +
                    "filter = paste0(\"^(\", paste(contexts, collapse = \"|\"), \")$\"))}})",
                "--args",
                "tests/testthat/test-alpha.R",
                "tests/testthat/test-beta.R",
            ),
            command.arguments,
        )

        val text = execute(root, command.arguments)
        assertContains(text, "AlphaTest")
        assertContains(text, "BetaTest")
        assertFalse(text.contains("UnrelatedTest"), text)
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

        val text = execute(root, command.arguments, succeeds = false)
        assertContains(text, "AlphaTest")
        assertContains(text, "BetaTest")
    }

    private fun fixture(name: String, block: (File) -> Unit) {
        assumeTrue(System.getProperty("affected.cliConformance") == "true")
        val source = File(fixtureRoot(), name)
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        val target = createTempDirectory("affected-cli-$name").toFile()
        try {
            assertTrue(source.copyRecursively(target, overwrite = true), "Could not copy $source")
            block(target)
        } finally {
            target.deleteRecursively()
        }
    }

    private fun fixtureRoot(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "conformance/cli-fixtures") }
        .firstOrNull(File::isDirectory)
        ?: File(System.getProperty("user.dir"), "conformance/cli-fixtures")

    private fun execute(directory: File, arguments: List<String>, succeeds: Boolean = true): String {
        val output = File.createTempFile("affected-cli-output", ".log")
        try {
            val process = ProcessBuilder(arguments)
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .start()
            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            val text = output.readText()
            assertTrue(completed, "Timed out: ${arguments.joinToString(" ")}\n$text")
            assertEquals(
                if (succeeds) 0 else 1,
                process.exitValue(),
                "Unexpected exit: ${arguments.joinToString(" ")}\n$text",
            )
            return text
        } finally {
            output.delete()
        }
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 180L
    }
}
