package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliUnittestSafetyConformanceTest {

    @Test
    fun `unittest SystemExit zero during import widens and remains a failure`() = fixture { root ->
        val exiting = File(root, "packages/alpha/test_exiting.py").apply {
            writeText("raise SystemExit(0)\n")
        }

        val execution = execute(root, unittestCommand(root, exiting).arguments)

        assertTrue(execution.completed, execution.output)
        assertFalse(execution.passed, execution.output)
        assertTrue(execution.output.contains("Affected unittest: full fallback (import)"), execution.output)
        assertTrue(File(root, "packages/alpha/consumer.marker").exists())
        assertTrue(File(root, "packages/alpha/other.marker").exists())
        assertFalse(File(root, "packages/beta/beta.marker").exists())
    }

    @Test
    fun `unittest SystemExit zero during module inspection cannot report success`() = fixture { root ->
        val inspected = File(root, "packages/alpha/test_inspected.py").apply {
            writeText(
                """
                import unittest
                from pathlib import Path

                class InspectedTest(unittest.TestCase):
                    def test_inspected(self):
                        Path(__file__).with_name("inspected.marker").write_text("ran", encoding="utf-8")

                def __getattr__(name):
                    if name == "load_tests":
                        raise SystemExit(0)
                    raise AttributeError(name)
                """.trimIndent() + "\n",
            )
        }

        val execution = execute(root, unittestCommand(root, inspected).arguments)

        assertTrue(execution.completed, execution.output)
        assertFalse(execution.passed, execution.output)
        assertTrue(execution.output.contains("Affected unittest: full fallback (module)"), execution.output)
        assertFalse(File(root, "packages/alpha/inspected.marker").exists())
    }

    @Test
    fun `unittest custom inspection errors widen and preserve other package failures`() = fixture { root ->
        val inspected = File(root, "packages/alpha/test_domain_error.py").apply {
            writeText(
                """
                import unittest

                class DomainError(Exception):
                    pass

                class DomainTest(unittest.TestCase):
                    def test_domain(self):
                        pass

                def __getattr__(name):
                    if name == "load_tests":
                        raise DomainError("ambiguous metadata")
                    raise AttributeError(name)
                """.trimIndent() + "\n",
            )
        }

        val execution = execute(
            root,
            unittestCommand(root, inspected, File(root, "packages/beta/test_beta.py")).arguments,
        )

        assertTrue(execution.completed, execution.output)
        assertFalse(execution.passed, execution.output)
        assertTrue(execution.output.contains("Affected unittest: full fallback (module)"), execution.output)
        assertTrue(File(root, "packages/beta/beta.marker").exists(), execution.output)
    }

    @Test
    fun `unittest SystemExit zero while counting tests cannot report success`() = fixture { root ->
        val counted = File(root, "packages/alpha/test_counted.py").apply {
            writeText(
                """
                import unittest
                from pathlib import Path

                class CountedTest(unittest.TestCase):
                    def countTestCases(self):
                        raise SystemExit(0)

                    def test_counted(self):
                        Path(__file__).with_name("counted.marker").write_text("ran", encoding="utf-8")
                """.trimIndent() + "\n",
            )
        }

        val execution = execute(root, unittestCommand(root, counted).arguments)

        assertTrue(execution.completed, execution.output)
        assertFalse(execution.passed, execution.output)
        assertFalse(File(root, "packages/alpha/counted.marker").exists())
    }

    @Test
    fun `unittest SystemExit zero during test execution cannot report success`() = fixture { root ->
        val exiting = File(root, "packages/alpha/test_running.py").apply {
            writeText(
                """
                import unittest

                class RunningTest(unittest.TestCase):
                    def test_running(self):
                        raise SystemExit(0)
                """.trimIndent() + "\n",
            )
        }

        val execution = execute(root, unittestCommand(root, exiting).arguments)

        assertTrue(execution.completed, execution.output)
        assertFalse(execution.passed, execution.output)
    }

    @Test
    fun `unittest SystemExit zero from a custom suite cannot report success`() = fixture { root ->
        File(root, "packages/alpha/__init__.py").writeText(
            """
            import unittest

            class ExitSuite(unittest.TestSuite):
                def run(self, result, debug=False):
                    raise SystemExit(0)

            def load_tests(loader, tests, pattern):
                return ExitSuite([unittest.FunctionTestCase(lambda: None)])
            """.trimIndent() + "\n",
        )

        val execution = execute(
            root,
            unittestCommand(root, File(root, "packages/alpha/test_alpha.py")).arguments,
        )

        assertTrue(execution.completed, execution.output)
        assertFalse(execution.passed, execution.output)
        assertTrue(execution.output.contains("Affected unittest: full fallback"), execution.output)
    }

    @Test
    fun `unittest custom suite cannot report success without running its declared tests`() = fixture { root ->
        File(root, "packages/alpha/__init__.py").writeText(
            """
            import unittest

            class NoOpSuite(unittest.TestSuite):
                def countTestCases(self):
                    return 1

                def run(self, result, debug=False):
                    return result

            def load_tests(loader, tests, pattern):
                return NoOpSuite()
            """.trimIndent() + "\n",
        )

        val execution = execute(
            root,
            unittestCommand(root, File(root, "packages/alpha/test_alpha.py")).arguments,
        )

        assertTrue(execution.completed, execution.output)
        assertFalse(execution.passed, execution.output)
        assertTrue(execution.output.contains("Affected unittest: expected 1 tests but ran 0"), execution.output)
    }

    @Test
    fun `unittest nested SystemExit during error formatting cannot report success`() = fixture { root ->
        File(root, "packages/alpha/__init__.py").writeText(
            """
            import unittest

            class UnsafeReason:
                def __str__(self):
                    raise SystemExit(0)

            class ExitSuite(unittest.TestSuite):
                def run(self, result, debug=False):
                    raise SystemExit(UnsafeReason())

            def load_tests(loader, tests, pattern):
                return ExitSuite([unittest.FunctionTestCase(lambda: None)])
            """.trimIndent() + "\n",
        )

        val execution = execute(
            root,
            unittestCommand(root, File(root, "packages/alpha/test_alpha.py")).arguments,
        )

        assertTrue(execution.completed, execution.output)
        assertFalse(execution.passed, execution.output)
        assertTrue(execution.output.contains("Affected unittest: unsafe test execution"), execution.output)
    }

    private fun fixture(block: (File) -> Unit) {
        assumeTrue(System.getProperty("affected.cliConformance") == "true")
        val source = File(fixtureRoot(), "unittest")
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        val target = createTempDirectory("affected-cli-unittest-safety").toFile()
        try {
            assertTrue(source.copyRecursively(target, overwrite = true), "Could not copy $source")
            block(target)
        } finally {
            target.deleteRecursively()
        }
    }

    private fun fixtureRoot(): File = CliConformanceRepository.configured.fixturesRoot()

    private fun unittestAdapter(): File =
        CliConformanceRepository.configured.repositoryFile("core/src/main/python/affected_unittest.py")

    private fun unittestCommand(root: File, vararg changed: File): CliCommand {
        val modules = PythonProjects.parse(root)
        val owners = changed.map { file ->
            modules.filter { module ->
                file.toPath().toAbsolutePath().normalize().startsWith(File(module.contentRoots.single()).toPath())
            }.maxBy { it.contentRoots.single().length }
        }.distinct()
        return pythonCommands(
            root.path,
            owners.map { "${it.executionId}:test" },
            owners,
            BuildChanges(
                files = changed.map(File::getPath),
                exactSelectionEligible = changed.mapTo(LinkedHashSet(), File::getPath),
                comparedToBase = true,
            ),
            unittestAdapter().toPath(),
        ).single()
    }

    private fun execute(directory: File, arguments: List<String>): Execution {
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
            return Execution(completed, process.exitValue(), text)
        } finally {
            output.delete()
        }
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 180L
    }

    private data class Execution(val completed: Boolean, val exitCode: Int, val output: String) {
        val passed: Boolean get() = completed && exitCode == 0
    }
}
